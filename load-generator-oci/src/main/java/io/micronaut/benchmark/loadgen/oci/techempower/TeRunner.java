package io.micronaut.benchmark.loadgen.oci.techempower;

import io.micronaut.benchmark.loadgen.oci.CompartmentCleaner;
import io.micronaut.benchmark.loadgen.oci.MdcTracker;
import io.micronaut.benchmark.loadgen.oci.OciLocation;
import io.micronaut.benchmark.loadgen.oci.PhaseTracker;
import io.micronaut.http.client.HttpClient;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

@Singleton
public final class TeRunner {
    private static final Logger LOG = LoggerFactory.getLogger(TeRunner.class);
    private final CompartmentCleaner compartmentCleaner;
    private final TeConfiguration configuration;
    private final TeInfrastructure.Factory infrastructureFactory;
    private final ObjectMapper objectMapper;
    private final List<Revision> revisions;
    private final List<OciLocation> locations;
    private final HttpClient httpClient;

    public TeRunner(
            CompartmentCleaner compartmentCleaner,
            TeConfiguration configuration,
            TeInfrastructure.Factory infrastructureFactory,
            ObjectMapper objectMapper,
            List<Revision> revisions,
            List<OciLocation> locations,
            HttpClient httpClient
    ) {
        this.compartmentCleaner = compartmentCleaner;
        this.configuration = configuration;
        this.infrastructureFactory = infrastructureFactory;
        this.objectMapper = objectMapper;
        this.revisions = revisions;
        this.locations = locations;
        this.httpClient = httpClient;
        if (revisions.isEmpty()) {
            throw new IllegalArgumentException();
        }
    }

    public void run() throws Exception {
        Collections.shuffle(locations);
        compartmentCleaner.cleanCompartments(locations, false);

        List<CompletableFuture<Results>> futures = new ArrayList<>();
        for (int i = 0; i < configuration.repetitions(); i++) {
            OciLocation location = locations.get(i % locations.size());

            Path outputDir = Path.of("techempower-output", String.valueOf(i));
            try {
                Files.createDirectories(outputDir);
            } catch (FileAlreadyExistsException ignored) {
            }

            String name = "run-" + i;

            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return MdcTracker.withMdc(name, () -> {
                        PhaseTracker phaseTracker = new PhaseTracker(objectMapper, outputDir);
                        try (TeInfrastructure infrastructure = infrastructureFactory.create(location, outputDir.resolve("infra"))) {
                            PhaseTracker.PhaseUpdater main = phaseTracker.updater("main");
                            infrastructure.start(main);
                            return infrastructure.run(outputDir, revisions);
                        }
                    });
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, Executors.newThreadPerTaskExecutor(Thread.ofPlatform().name(name).factory())));
        }

        CompletableFuture<Void> completed = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        if (completed.isCompletedExceptionally()) {
            for (CompletableFuture<Results> cf : futures) {
                cf.cancel(true);
            }
            completed.get();
            return;
        }

        List<Results> results = new ArrayList<>();
        for (CompletableFuture<Results> future : futures) {
            results.add(future.get());
        }

        String uri = TeInfrastructure.uploadResults(httpClient, Results.merge(results));
        LOG.info("Combined results: {}", uri);
    }
}
