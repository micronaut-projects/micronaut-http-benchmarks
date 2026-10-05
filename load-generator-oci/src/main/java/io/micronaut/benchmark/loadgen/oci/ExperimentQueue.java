package io.micronaut.benchmark.loadgen.oci;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.benchmark.api.BatchRequest;
import io.micronaut.benchmark.api.ExperimentRequest;
import io.micronaut.benchmark.api.Nix;
import io.micronaut.benchmark.api.RunRecord;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.event.ShutdownEvent;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The only owner of environment operations. Job history is deliberately not recovered on restart.
 */
@Singleton
public final class ExperimentQueue implements AutoCloseable {
    private final JsonMapper mapper;
    private final Nix nix;
    private final Duration idleTimeout;
    private final Supplier<? extends ExecutionEnvironment> provider;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, Batch> batches = new ConcurrentHashMap<>();
    private final BlockingQueue<Batch> queue = new LinkedBlockingQueue<>();
    private final Thread worker;
    private final Runnable stopDaemon;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private volatile boolean stopping;
    private volatile boolean closed;
    private volatile Job active;
    private volatile ExecutionEnvironment environment;
    private Instant lastActivity = Instant.now();

    @Inject
    public ExperimentQueue(JsonMapper mapper, Nix nix, BeanProvider<OciEnvironment> provider, ApplicationContext context) throws IOException {
        this(mapper, nix, Path.of("output/daemon"), Duration.ofHours(2), provider::get,
                () -> Thread.ofPlatform().name("daemon-shutdown").start(context::close));
    }

    ExperimentQueue(JsonMapper mapper, Nix nix, Path directory, Duration idleTimeout, Supplier<? extends ExecutionEnvironment> provider, Runnable stopDaemon) throws IOException {
        this.mapper = mapper;
        this.nix = nix;
        this.idleTimeout = idleTimeout;
        this.provider = provider;
        this.stopDaemon = stopDaemon;
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve("daemon.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = lockChannel.tryLock();
        if (lock == null) {
            lockChannel.close();
            throw new IOException("Another benchmark daemon owns this state directory");
        }
        worker = Thread.ofVirtual().name("experiment-queue").start(this::loop);
    }

    public synchronized BatchView submit(BatchRequest request) throws Exception {
        if (stopping) {
            throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Daemon is shutting down");
        }
        List<Job> accepted = new ArrayList<>();
        try {
            for (ExperimentRequest experiment : request.experiments()) {
                Path root = Files.createDirectories(Path.of(experiment.outputRoot())).toRealPath();
                String id = UUID.randomUUID().toString();
                Path directory = Files.createDirectory(root.resolve(id));
                Job job = new Job(id, experiment, directory);
                accepted.add(job);
                job.save();
                jobs.put(id, job);
            }
        } catch (Exception e) {
            for (Job job : accepted) job.finish("FAILED", e);
            throw e;
        }
        Batch batch = new Batch(accepted);
        batches.put(batch.id, batch);
        queue.add(batch);
        return batch.view();
    }

    public List<RunRecord> runs() {
        return jobs.values().stream().map(Job::view).sorted(Comparator.comparing(RunRecord::submitted)).toList();
    }

    public RunRecord run(String id) {
        return job(id).view();
    }

    private Job job(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            throw new IllegalArgumentException("Unknown run: " + id);
        }
        return job;
    }

    public BatchView batch(String id) {
        Batch batch = batches.get(id);
        if (batch == null) {
            throw new IllegalArgumentException("Unknown batch: " + id);
        }
        return batch.view();
    }

    public RunRecord cancel(String id) throws IOException {
        Job job = job(id);
        synchronized (job) {
            if (job.finished != null || job.cancelled) {
                return job.view();
            }
            job.cancelled = true;
            if (active == job) {
                worker.interrupt();
            } else {
                job.finish("CANCELLED", null);
            }
            return job.view();
        }
    }

    public synchronized void stopAccepting() {
        stopping = true;
    }

    public Map<String, Object> environmentStatus() {
        String identity = environment == null ? null : environment.id();
        return Map.of("environmentId", identity == null ? "" : identity,
                "activeRun", active == null ? "" : active.id, "queuedOperations", queue.size(),
                "stopping", stopping, "pid", ProcessHandle.current().pid());
    }

    private ExecutionEnvironment environment() {
        if (environment == null) {
            environment = provider.get();
        }
        return environment;
    }

    private void loop() {
        try {
            while (!stopping) {
                try {
                    long remaining = idleTimeout.minus(Duration.between(lastActivity, Instant.now())).toNanos();
                    Batch next = queue.poll(Math.max(0, remaining), TimeUnit.NANOSECONDS);
                    if (next != null) {
                        execute(next);
                        lastActivity = Instant.now();
                    } else {
                        synchronized (this) {
                            // Submission and expiry must be atomic: never abandon an accepted job.
                            if (queue.isEmpty() && !stopping) {
                                stopping = true;
                            }
                        }
                    }
                } catch (InterruptedException ignored) { /* cancellation is recorded on the active job */ } catch (
                        Exception e) {
                    LoggerFactory.getLogger(getClass()).error("Environment operation failed", e);
                    lastActivity = Instant.now();
                }
            }
        } catch (Throwable fatal) {
            stopping = true;
            LoggerFactory.getLogger(getClass()).error("Experiment worker failed; stopping the daemon", fatal);
            for (Job job : jobs.values())
                if (!job.view().terminal()) {
                    try {
                        job.finish("FAILED", fatal);
                    } catch (IOException writeFailure) {
                        fatal.addSuppressed(writeFailure);
                    }
                }
            for (Batch batch : batches.values())
                if (!batch.finished) {
                    batch.failure = fatal.toString();
                    batch.finished = true;
                }
            queue.clear();
        } finally {
            Thread.interrupted();
            if (environment != null) {
                try {
                    environment.down();
                } catch (Exception e) {
                    LoggerFactory.getLogger(getClass()).error("Compartment cleanup failed", e);
                }
            }
            if (!closed) {
                stopDaemon.run();
            }
        }
    }

    private void execute(Batch batch) {
        Exception failure = null;
        try {
            for (int round = 1; batch.jobs.stream().anyMatch(j -> !j.view().terminal()) && !stopping; round++) {
                var scheduled = new ArrayList<>(batch.jobs);
                if (round > 1) Collections.shuffle(scheduled);
                for (Job job : scheduled) {
                    if (stopping) {
                        break;
                    }
                    synchronized (job) {
                        if (job.cancelled || job.finished != null) {
                            continue;
                        }
                        active = job;
                    }
                    execute(job, round);
                    synchronized (job) {
                        // cancel() only interrupts while holding this lock, so no interrupt for this job can
                        // arrive after this point. A cancel that raced with the end of the repetition left the
                        // job in WAITING_FOR_REPETITION; finish it here.
                        active = null;
                        Thread.interrupted();
                        if (job.cancelled && job.finished == null) {
                            job.finish("CANCELLED", null);
                        }
                    }
                    if (job.view().terminal() && !"SUCCEEDED".equals(job.view().state())) {
                        throw new IOException("Batch stopped after " + job.id + ": " + job.view().state());
                    }
                }
            }
        } catch (Exception e) {
            failure = e;
        } catch (Error fatal) {
            failure = new Exception("Fatal execution failure", fatal);
            throw fatal;
        } finally {
            active = null;
            Thread.interrupted();
            for (Job job : batch.jobs)
                if (!job.view().terminal()) {
                    try {
                        job.finish("CANCELLED", failure);
                    } catch (IOException e) {
                        failure = e;
                    }
                }
            batch.failure = failure == null ? null : failure.toString();
            batch.finished = true;
        }
    }

    private void execute(Job job, int repetition) {
        try {
            if (job.prepared == null) {
                job.started = Instant.now();
                job.state("BUILDING");
                try (OutputStream log = Files.newOutputStream(job.directory.resolve("build.log"))) {
                    job.experiment = nix.realize(Path.of(job.request.derivation()), job.request.output(), job.directory.resolve(".nix/experiment"), log).toString();
                }
                job.state("PREPARING_EXPERIMENT");
                job.prepared = PreparedExperiment.load(Path.of(job.experiment), job.directory, mapper);
            }
            PreparedExperiment prepared = job.prepared;
            ExecutionEnvironment environment = environment();
            environment.validate(prepared);
            PhaseTracker tracker = new PhaseTracker(mapper, job.directory);
            try (AutoCloseable progress = tracker.start()) {
                PhaseTracker.PhaseUpdater updater = (phase, percent, display) -> {
                    tracker.updater(job.id).update(phase, percent, display);
                    try {
                        job.state(phase.name());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                };
                try {
                    environment.up(updater);
                } finally {
                    job.environmentId = environment.id();
                    job.save();
                }
                if (job.cancelled) {
                    throw new InterruptedException("Run cancelled");
                }
                if (prepared.search() == null) {
                    environment.execute(prepared, job.directory, updater);
                } else {
                    new ThroughputRunner(mapper).repetition(environment, prepared, job.directory, repetition, updater);
                }
            }
            if (!job.cancelled && prepared.search() != null && repetition < prepared.search().repetitions()) {
                job.state("WAITING_FOR_REPETITION");
            } else {
                job.finish(job.cancelled ? "CANCELLED" : "SUCCEEDED", null);
            }
        } catch (Throwable e) {
            try {
                job.finish(job.cancelled || stopping ? "CANCELLED" : "FAILED", e);
            } catch (IOException writeFailure) {
                LoggerFactory.getLogger(getClass()).error("Could not save run outcome", writeFailure);
            }
            if (e instanceof EnvironmentInvalidException) {
                stopAccepting();
            }
            if (e instanceof Error fatal) {
                throw fatal;
            }
        }
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    void shutdown(ShutdownEvent event) throws Exception {
        // Complete OCI cleanup before the context destroys its pollers, clients, or executors.
        close();
    }

    @Override
    @PreDestroy
    public void close() throws Exception {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            stopping = true;
        }
        Job current = active;
        if (current != null) {
            synchronized (current) {
                current.cancelled = true;
            }
        }
        queue.clear();
        worker.interrupt();
        worker.join();
        for (Job job : jobs.values())
            if (!job.view().terminal()) {
                job.finish("CANCELLED", null);
            }
        lock.release();
        lockChannel.close();
    }

    @Serdeable
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BatchView(String id, List<RunRecord> runs, boolean finished, String failure) {
    }

    private final class Batch {
        final String id = UUID.randomUUID().toString();
        final List<Job> jobs;
        volatile boolean finished;
        volatile String failure;

        Batch(List<Job> jobs) {
            this.jobs = List.copyOf(jobs);
        }

        BatchView view() {
            return new BatchView(id, jobs.stream().map(Job::view).toList(), finished, failure);
        }
    }

    private final class Job {
        final String id;
        final ExperimentRequest request;
        final Path directory;
        final Instant submitted = Instant.now();
        final Map<String, Instant> timings = new LinkedHashMap<>();
        String state = "QUEUED", experiment, environmentId, failure;
        Instant started, finished;
        volatile boolean cancelled;
        PreparedExperiment prepared;

        Job(String id, ExperimentRequest request, Path directory) {
            this.id = id;
            this.request = request;
            this.directory = directory;
        }

        synchronized RunRecord view() {
            return new RunRecord(id, request, directory.toString(), state, experiment, environmentId, submitted, started, finished, failure, Map.copyOf(timings));
        }

        synchronized void state(String state) throws IOException {
            this.state = state;
            timings.putIfAbsent(state, Instant.now());
            save();
        }

        synchronized void finish(String state, Throwable error) throws IOException {
            if (error != null) {
                try (PrintWriter diagnostics = new PrintWriter(Files.newBufferedWriter(directory.resolve("failure.log")))) {
                    error.printStackTrace(diagnostics);
                }
            }
            finished = Instant.now();
            failure = error == null ? null : error.toString();
            state(state);
        }

        synchronized void save() throws IOException {
            Path temporary = directory.resolve(".run.json.tmp");
            mapper.writeValue(temporary.toFile(), view());
            Files.move(temporary, directory.resolve("run.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
