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
        runs = metadata.suite().runs().stream().map(run -> new NixFrameworkRun(run, metadata.suite().asyncProfiler(), asyncProfilerHelper)).toList();
    }

    @Override
    public List<? extends FrameworkRun> getRuns() {
        return runs;
    }

    private record NixFrameworkRun(NixFrameworkMetadata metadata, boolean asyncProfiler, AsyncProfilerHelper asyncProfilerHelper) implements FrameworkRun {
        @Override
        public String type() {
            return metadata.type();
        }

        @Override
        public String name() {
            return metadata.name() + (asyncProfiler ? "-async-profiler" : "");
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
            NixRunSet.setupAndRun(SUT_SERVICE, asyncProfiler, asyncProfilerHelper, benchmarkServerClient, outputDirectory, log, benchmarkClosure, progress);
        }
    }

    static void setupAndRun(String service, boolean asyncProfiler, AsyncProfilerHelper asyncProfilerHelper, CommandRunner benchmarkServerClient, Path outputDirectory, OutputListener.Write log,
                            FrameworkRun.BenchmarkClosure benchmarkClosure, PhaseTracker.PhaseUpdater progress) throws Exception {
        progress.update(BenchmarkPhase.DEPLOYING_SERVER);
        benchmarkServerClient.runAndCheck("systemctl restart -- " + service, log);
        benchmarkClosure.benchmark(progress);
        if (asyncProfiler) {
            benchmarkServerClient.runAndCheck("systemctl stop -- " + service, log);
            benchmarkServerClient.download(AsyncProfilerHelper.REMOTE_PROFILE_PATH, outputDirectory.resolve(AsyncProfilerHelper.PROFILE_FILE_NAME));
            asyncProfilerHelper.convert(outputDirectory);
        } else {
            benchmarkServerClient.runAndCheck("systemctl --quiet is-active -- " + service, log);
        }
    }

}

record NixFrameworkMetadata(String type, String name, JsonNode parameters,
                            String nixosConfiguration) {
}
