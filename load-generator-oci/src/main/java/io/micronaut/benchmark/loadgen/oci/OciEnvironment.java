package io.micronaut.benchmark.loadgen.oci;

import jakarta.inject.Singleton;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

@Singleton
public final class OciEnvironment implements ExecutionEnvironment {
    private final Infrastructure.Factory factory;
    private final InfrastructureMetadata metadata;
    private final OciLocation location;
    private final CompartmentCleaner cleaner;
    private final JsonMapper mapper;
    private Infrastructure infrastructure;
    private final String id = UUID.randomUUID().toString();

    public OciEnvironment(Infrastructure.Factory factory, InfrastructureMetadata metadata, OciLocation location,
                          CompartmentCleaner cleaner, JsonMapper mapper) {
        this.factory = factory;
        this.metadata = metadata;
        this.location = location;
        this.cleaner = cleaner;
        this.mapper = mapper;
    }

    public void validate(PreparedExperiment experiment) {
        var requirements = experiment.requirements();
        if (!metadata.instanceType(Infrastructure.BENCHMARK_SERVER_INSTANCE_TYPE).equals(requirements.instanceType())
                || !metadata.kernel().equals(requirements.kernel())
                || !new HashSet<>(requirements.attachments()).equals(new HashSet<>(factory.attachments().stream().map(Infrastructure.Attachment::name).toList()))) {
            throw new IllegalArgumentException("Experiment requires different infrastructure; restart the daemon with compatible infrastructure configuration");
        }
    }

    public String id() {
        return id;
    }

    public void up(PhaseTracker.PhaseUpdater progress) throws Exception {
        if (infrastructure != null) {
            if (!infrastructure.usable()) {
                throw new EnvironmentInvalidException("Infrastructure is unusable", null);
            }
            return;
        }
        try {
            cleaner.cleanCompartment(location, false);
            Path logs = Files.createDirectories(Path.of("output/daemon").resolve("environments").resolve(id));
            infrastructure = factory.create(location, logs);
            infrastructure.start(progress);
        } catch (Exception failure) {
            throw new EnvironmentInvalidException("Infrastructure startup failed", failure);
        }
    }

    public void execute(PreparedExperiment experiment, Path directory, PhaseTracker.PhaseUpdater progress) throws Exception {
        mapper.writeValue(directory.resolve("environment.json").toFile(), Map.of(
                "id", id, "location", location, "infrastructure", metadata.document(),
                "attachments", factory.attachments().stream().map(Infrastructure.Attachment::name).toList()));
        infrastructure.run(directory, experiment, progress);
    }

    public void down() throws Exception {
        Exception failure = null;
        if (infrastructure != null) {
            try {
                infrastructure.close();
            } catch (Exception e) {
                failure = e;
            }
        }
        try {
            cleaner.cleanCompartment(location, false);
        } catch (Exception e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
