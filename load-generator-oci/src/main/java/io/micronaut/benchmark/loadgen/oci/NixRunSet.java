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

    public NixRunSet(BenchmarkMetadata metadata) {
        runs = metadata.suite().runs().stream().map(NixFrameworkRun::new).toList();
    }

    @Override
    public List<? extends FrameworkRun> getRuns() {
        return runs;
    }

    private record NixFrameworkRun(NixFrameworkMetadata metadata) implements FrameworkRun {
        @Override
        public String type() {
            return metadata.type();
        }

        @Override
        public String name() {
            return metadata.name();
        }

        @Override
        public JsonNode parameters() {
            return metadata.parameters();
        }

        @Override
        public String nixosConfiguration() {
            return metadata.nixosConfiguration();
        }

        @Override
        public void setupAndRun(CommandRunner benchmarkServerClient, Path outputDirectory, OutputListener.Write log,
                                BenchmarkClosure benchmarkClosure, PhaseTracker.PhaseUpdater progress) throws Exception {
            NixRunSet.setupAndRun(SUT_SERVICE, benchmarkServerClient, log, benchmarkClosure, progress);
        }
    }

    static void setupAndRun(String service, CommandRunner benchmarkServerClient, OutputListener.Write log,
                            FrameworkRun.BenchmarkClosure benchmarkClosure, PhaseTracker.PhaseUpdater progress) throws Exception {
        progress.update(BenchmarkPhase.DEPLOYING_SERVER);
        benchmarkServerClient.runAndCheck("systemctl restart -- " + service, log);
        benchmarkClosure.benchmark(progress);
        benchmarkServerClient.runAndCheck("systemctl --quiet is-active -- " + service, log);
    }
}

record NixFrameworkMetadata(String type, String name, JsonNode parameters,
                            String nixosConfiguration) {
}
