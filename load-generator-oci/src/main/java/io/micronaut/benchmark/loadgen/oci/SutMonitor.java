package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.ProcessBuilder;
import io.micronaut.benchmark.loadgen.oci.cmd.ProcessHandle;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.bouncycastle.util.test.UncloseableOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Monitor the server-under-test system.
 */
@Singleton
public final class SutMonitor {
    private static final Logger LOG = LoggerFactory.getLogger(SutMonitor.class);

    private final MeminfoConfiguration meminfoConfiguration;
    private final ExecutorService executor;

    public SutMonitor(MeminfoConfiguration meminfoConfiguration, @Named(TaskExecutors.BLOCKING) ExecutorService executor) {
        this.meminfoConfiguration = meminfoConfiguration;
        this.executor = executor;
    }

    @SuppressWarnings("UnusedReturnValue")
    public <R> R monitorAndRun(
            CommandRunner session,
            Path outputDirectory,
            Callable<R> task
    ) throws Exception {
        if (meminfoConfiguration.enabled()) {
            try (OutputStream meminfo = Files.newOutputStream(outputDirectory.resolve("meminfo.log"))) {

                Future<Object> future = executor.submit(MdcTracker.copyMdc(() -> {
                    try {
                        while (!Thread.interrupted()) {
                            meminfo.write((Instant.now().toString() + "\n").getBytes(StandardCharsets.UTF_8));
                            try (ProcessBuilder builder = session.builder("cat /proc/meminfo")) {
                                builder.setOut(new UncloseableOutputStream(meminfo));
                                builder.setErr(new UncloseableOutputStream(meminfo));
                                try (ProcessHandle handle = builder.start()) {
                                    handle.waitFor().check();
                                }
                            }
                            TimeUnit.MILLISECONDS.sleep(meminfoConfiguration.interval().toMillis());
                        }
                    } catch (InterruptedException | InterruptedIOException ignored) {
                    } catch (Exception e) {
                        LOG.warn("Failed to monitor meminfo", e);
                    }
                    return null;
                }));
                try {
                    return task.call();
                } finally {
                    future.cancel(true);
                }
            }
        } else {
            return task.call();
        }
    }
}
