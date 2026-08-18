package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import jakarta.inject.Singleton;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;

@Singleton
public final class NixRunSet implements FrameworkRunSet {
    private static final String SUT_SERVICE = "sut.service";

    private final List<NixFrameworkRun> runs;

    public NixRunSet(BenchmarkMetadata metadata, AsyncProfilerHelper asyncProfilerHelper) {
        runs = metadata.suite().runs().stream().map(run -> new NixFrameworkRun(run, asyncProfilerHelper)).toList();
    }

    @Override
    public List<? extends FrameworkRun> getRuns() {
        return runs;
    }

    private record NixFrameworkRun(NixFrameworkMetadata metadata, AsyncProfilerHelper asyncProfilerHelper) implements FrameworkRun {
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
                configurationActivator.activate(pgo.optimizedConfiguration(), progress);
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
}

record NixFrameworkMetadata(String type, String name, JsonNode parameters, String nixosConfiguration,
                            boolean asyncProfiler, PgoMetadata pgo) {
    record PgoMetadata(String optimizedConfiguration) {
    }
}
