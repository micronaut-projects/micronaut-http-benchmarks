package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

@Singleton
public final class NixRunSet implements FrameworkRunSet {
    private static final String SUT_SERVICE = "sut.service";
    private static final Logger LOG = LoggerFactory.getLogger(NixRunSet.class);

    private final List<NixFrameworkRun> runs;

    public NixRunSet(BenchmarkMetadata metadata, Nix nix) {
        runs = metadata.suite().runs().stream().map(run -> new NixFrameworkRun(run, nix)).toList();
    }

    @Override
    public List<? extends FrameworkRun> getRuns() {
        return runs;
    }

    private record NixFrameworkRun(NixFrameworkMetadata metadata, Nix nix) implements FrameworkRun {
        @Override
        public String type() {
            return metadata.type();
        }

        @Override
        public String name() {
            return metadata.name() + (metadata.profiling() == null ? "" : "-profile");
        }

        @Override
        public JsonNode parameters() {
            return metadata.parameters();
        }

        @Override
        public Profiling profiling() {
            NixFrameworkMetadata.ProfilingMetadata profiling = metadata.profiling();
            return profiling == null ? null : new Profiling(
                    profiling.tool(),
                    profiling.artifact(),
                    profiling.injectedArtifact(),
                    profiling.symbolDirectory()
            );
        }

        @Override
        public List<NixosConfiguration> nixosConfigurations() {
            NixFrameworkMetadata.PgoMetadata pgo = metadata.pgo();
            return pgo == null
                    ? List.of(new NixosConfiguration(metadata.nixosConfiguration(), false))
                    : List.of(
                            new NixosConfiguration(metadata.nixosConfiguration(), false),
                            new NixosConfiguration(pgo.optimizedConfiguration(), true)
                    );
        }

        @Override
        public void setupAndRun(CommandRunner benchmarkServerClient, Path outputDirectory, OutputListener.Write log,
                                BenchmarkClosure benchmarkClosure, ConfigurationActivator configurationActivator,
                                PhaseTracker.PhaseUpdater progress) throws Exception {
            progress.update(BenchmarkPhase.STARTING_SERVER);
            NixFrameworkMetadata.PgoMetadata pgo = metadata.pgo();
            if (pgo != null) {
                benchmarkServerClient.runAndCheck("systemctl restart -- " + SUT_SERVICE, log);
                try {
                    progress.update(BenchmarkPhase.PGO);
                    benchmarkClosure.pgoLoad(progress);
                } finally {
                    benchmarkServerClient.runAndCheck("systemctl stop -- " + SUT_SERVICE, log);
                }
                Path localPgoDirectory = outputDirectory.resolve("pgo");
                deleteRecursively(localPgoDirectory);
                benchmarkServerClient.downloadRecursive(pgo.pgoDirectory(), localPgoDirectory);
                NixCacheAccess cache = configurationActivator.resolve(pgo.optimizedConfiguration());
                OutputListener pgoLog = new OutputListener.Log(LOG, Level.INFO);
                Path pgoStorePath = nix.addStorePath(pgoLog, localPgoDirectory);
                Path pgoOutput = nix.buildPgoOutput(pgoLog, pgo.optimizedConfiguration(), pgoStorePath);
                nix.uploadOutputCache(pgoLog, cache.writeUri(), pgoOutput);
                configurationActivator.activate(new Activation(pgo.optimizedConfiguration(), pgoOutput.toString(), progress));
                progress.update(BenchmarkPhase.STARTING_SERVER);
            }
            benchmarkServerClient.runAndCheck("systemctl restart -- " + SUT_SERVICE, log);
            benchmarkClosure.benchmark(progress);
            Profiling profiling = profiling();
            if (profiling != null) {
                benchmarkServerClient.runAndCheck("systemctl stop -- " + SUT_SERVICE, log);
                collectProfilingArtifacts(benchmarkServerClient, outputDirectory, profiling);
            } else {
                benchmarkServerClient.runAndCheck("systemctl --quiet is-active -- " + SUT_SERVICE, log);
            }
        }
    }

    private static void collectProfilingArtifacts(CommandRunner client, Path outputDirectory, FrameworkRun.Profiling profiling) throws Exception {
        Path artifact = outputDirectory.resolve(profiling.artifact());
        Path artifactStaging = stagingPath(artifact);
        Path injectedArtifact = profiling.injectedArtifact() == null ? null : outputDirectory.resolve(profiling.injectedArtifact());
        Path injectedArtifactStaging = injectedArtifact == null ? null : stagingPath(injectedArtifact);
        Path symbolDirectory = profiling.symbolDirectory() == null ? null : outputDirectory.resolve(profiling.symbolDirectory());
        Path symbolDirectoryStaging = symbolDirectory == null ? null : stagingPath(symbolDirectory);

        try {
            deletePaths(artifact, injectedArtifact, symbolDirectory, artifactStaging, injectedArtifactStaging, symbolDirectoryStaging);
            client.download(profiling.remoteArtifactPath(), artifactStaging);
            requireNonEmptyFile(artifactStaging);
            if (injectedArtifact != null) {
                client.download(profiling.remoteInjectedArtifactPath(), injectedArtifactStaging);
                requireNonEmptyFile(injectedArtifactStaging);
                client.downloadRecursive(profiling.remoteSymbolDirectoryPath(), symbolDirectoryStaging);
                requireNonEmptyDirectory(symbolDirectoryStaging);
                publish(symbolDirectoryStaging, symbolDirectory);
                publish(injectedArtifactStaging, injectedArtifact);
            }
            publish(artifactStaging, artifact);
        } catch (Exception failure) {
            try {
                deletePaths(artifact, injectedArtifact, symbolDirectory, artifactStaging, injectedArtifactStaging, symbolDirectoryStaging);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static Path stagingPath(Path destination) {
        return destination.resolveSibling("." + destination.getFileName() + ".staging");
    }

    private static void requireNonEmptyFile(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            throw new IOException("Downloaded profiling artifact is not a non-empty file: " + file);
        }
    }

    private static void requireNonEmptyDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("Downloaded profiling symbols are not a directory: " + directory);
        }
        try (var entries = Files.list(directory)) {
            if (entries.findAny().isEmpty()) {
                throw new IOException("Downloaded profiling symbol directory is empty: " + directory);
            }
        }
    }

    private static void publish(Path staging, Path destination) throws IOException {
        try {
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException atomicFailure) {
            try {
                Files.move(staging, destination);
            } catch (IOException failure) {
                failure.addSuppressed(atomicFailure);
                throw failure;
            }
        }
    }

    private static void deletePaths(Path... paths) throws IOException {
        for (Path path : paths) {
            if (path != null) {
                deleteRecursively(path);
            }
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

record NixFrameworkMetadata(String type, String name, JsonNode parameters, String nixosConfiguration,
                            ProfilingMetadata profiling, PgoMetadata pgo) {
    record ProfilingMetadata(String tool, String artifact, @Nullable String injectedArtifact,
                             @Nullable String symbolDirectory) {
    }
    record PgoMetadata(String optimizedConfiguration, String pgoDirectory) {
    }
}
