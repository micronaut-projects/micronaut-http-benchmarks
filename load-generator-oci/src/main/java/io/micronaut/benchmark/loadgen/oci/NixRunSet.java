package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.benchmark.loadgen.oci.cmd.ProcessBuilder;
import jakarta.inject.Singleton;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

@Singleton
public final class NixRunSet implements FrameworkRunSet {
    private final List<NixFrameworkRun> runs;

    public NixRunSet(BenchmarkMetadata metadata) {
        runs = metadata.frameworkRuns().stream().map(NixFrameworkRun::new).toList();
    }

    static String parseInvocationId(String output) {
        String invocationId = output.trim();
        if (!invocationId.matches("[0-9A-Fa-f]{32}")) {
            throw new IllegalArgumentException("Invalid systemd invocation ID");
        }
        return invocationId;
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
            String service = metadata.serviceName() + ".service";
            progress.update(BenchmarkPhase.DEPLOYING_SERVER);
            benchmarkServerClient.runAndCheck("systemctl restart -- " + service, log);
            String invocationId = invocationId(benchmarkServerClient, service);

            Exception benchmarkFailure = null;
            try {
                benchmarkClosure.benchmark(progress);
                benchmarkServerClient.runAndCheck("systemctl --quiet is-active -- " + service, log);
            } catch (Exception e) {
                benchmarkFailure = e;
                throw e;
            } finally {
                try (ProcessBuilder builder = benchmarkServerClient.builder(
                        "journalctl --no-pager --output cat _SYSTEMD_INVOCATION_ID=" + invocationId)) {
                    builder.forwardOutput(log).start().waitFor().check();
                } catch (Exception e) {
                    if (benchmarkFailure != null) {
                        benchmarkFailure.addSuppressed(e);
                    } else {
                        throw e;
                    }
                }
            }
        }

        private static String invocationId(CommandRunner benchmarkServerClient, String service) throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            benchmarkServerClient.runAndCheck(
                    "systemctl show --property InvocationID --value -- " + service,
                    new OutputListener.Write(output)
            );
            return parseInvocationId(output.toString(StandardCharsets.UTF_8));
        }
    }
}

record NixFrameworkMetadata(String type, String name, JsonNode parameters,
                            String nixosConfiguration, String serviceName) {
}
