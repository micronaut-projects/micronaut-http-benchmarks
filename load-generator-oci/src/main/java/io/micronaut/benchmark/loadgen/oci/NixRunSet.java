package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

@Singleton
public final class NixRunSet implements FrameworkRunSet {
    private static final String SUT_SERVICE = "sut.service";
    private static final Logger LOG = LoggerFactory.getLogger(NixRunSet.class);

    private final List<NixFrameworkRun> runs;

    public NixRunSet(BenchmarkMetadata metadata, AsyncProfilerHelper asyncProfilerHelper, Nix nix) {
        runs = metadata.suite().runs().stream().map(run -> new NixFrameworkRun(run, asyncProfilerHelper, nix)).toList();
    }

    @Override
    public List<? extends FrameworkRun> getRuns() {
        return runs;
    }

    private record NixFrameworkRun(NixFrameworkMetadata metadata, AsyncProfilerHelper asyncProfilerHelper, Nix nix) implements FrameworkRun {
        @Override
        public String type() {
            return metadata.type();
        }

        @Override
        public String name() {
            return metadata.name() + (metadata.asyncProfiler() ? "-async-profiler" : "");
        }

        @Override
        public JsonNode parameters() {
            return metadata.parameters();
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
            if (metadata.asyncProfiler()) {
                benchmarkServerClient.runAndCheck("systemctl stop -- " + SUT_SERVICE, log);
                benchmarkServerClient.download(AsyncProfilerHelper.REMOTE_PROFILE_PATH, outputDirectory.resolve(AsyncProfilerHelper.PROFILE_FILE_NAME));
                asyncProfilerHelper.convert(outputDirectory);
            } else {
                benchmarkServerClient.runAndCheck("systemctl --quiet is-active -- " + SUT_SERVICE, log);
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
                            boolean asyncProfiler, PgoMetadata pgo) {
    record PgoMetadata(String optimizedConfiguration, String pgoDirectory) {
    }
}
