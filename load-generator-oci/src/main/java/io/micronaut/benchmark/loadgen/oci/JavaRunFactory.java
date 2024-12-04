package io.micronaut.benchmark.loadgen.oci;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.scp.client.ScpClientCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Factory for {@link FrameworkRun}s that use a {@code java} command to run a shadow jar. This class handles things
 * like JVM flags and async-profiler setup. It can also run the jar using native-image.
 */
@Singleton
public final class JavaRunFactory {
    private static final Logger LOG = LoggerFactory.getLogger(JavaRunFactory.class);
    private static final String SHADOW_JAR_LOCATION = "shadow.jar";
    private static final String PROFILER_LOCATION = "/tmp/libasyncProfiler.so";

    private final HotspotConfiguration hotspotConfiguration;
    private final NativeImageConfiguration nativeImageConfiguration;
    private final AsyncProfilerConfiguration asyncProfilerConfiguration;
    private final PerfStatConfiguration perfStatConfiguration;

    public JavaRunFactory(HotspotConfiguration hotspotConfiguration, NativeImageConfiguration nativeImageConfiguration, AsyncProfilerConfiguration asyncProfilerConfiguration, PerfStatConfiguration perfStatConfiguration) {
        this.hotspotConfiguration = hotspotConfiguration;
        this.nativeImageConfiguration = nativeImageConfiguration;
        this.asyncProfilerConfiguration = asyncProfilerConfiguration;
        this.perfStatConfiguration = perfStatConfiguration;
    }

    private static String optionsToString(String opts) {
        return opts.replaceAll("[:+=-]", "")
                .replace(" nofallback", "")
                .replaceAll(" +", "-")
                .toLowerCase(Locale.ROOT);
    }

    /**
     * New builder for java-based runs.
     *
     * @param typePrefix Prefix for the {@link FrameworkRun#type()}.
     * @return The builder
     */
    public RunBuilder createJavaRuns(String typePrefix) {
        return new RunBuilder(typePrefix);
    }

    public final class RunBuilder {
        private final String typePrefix;
        private Path shadowJar;
        @Nullable
        private String configString;
        @Nullable
        private Object compileConfiguration;
        private byte[] boundLine;
        private final String additionalNativeImageOptions;
        @Nullable
        private String args;

        private RunBuilder(String typePrefix) {
            this.typePrefix = typePrefix;
            this.additionalNativeImageOptions = nativeImageConfiguration.prefixOptions().getOrDefault(typePrefix, "");
        }

        /**
         * Location of the jar to run.
         */
        public RunBuilder shadowJar(Path shadowJar) {
            if (!Files.exists(shadowJar)) {
                throw new IllegalArgumentException("File does not exist: " + shadowJar);
            }
            this.shadowJar = shadowJar;
            return this;
        }

        /**
         * The string representing the compile configuration. Used for the directory name of the output.
         */
        public RunBuilder configString(String configString) {
            this.configString = configString;
            return this;
        }

        /**
         * The compile configuration object for the benchmark index. This is serialized to JSON for the index.
         */
        public RunBuilder compileConfiguration(Object compileConfiguration) {
            this.compileConfiguration = compileConfiguration;
            return this;
        }

        /**
         * Log message when the server is bound and ready for requests. We wait for this log message before starting the
         * benchmark.
         */
        public RunBuilder boundOn(String message) {
            this.boundLine = message.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        /**
         * Application arguments to pass to the SUT.
         */
        public RunBuilder args(String args) {
            this.args = args;
            return this;
        }

        /**
         * Build the runs for this jar. This returns multiple runs if multiple different JVM flag choices are
         * configured, and for native-image.
         */
        public Stream<FrameworkRun> build() {
            return Stream.concat(
                    // one run for each JVM option choice to test
                    hotspotConfiguration.optionChoices().stream().map(hotspotOptions -> new FrameworkRun() {
                        @Override
                        public String type() {
                            return typePrefix + "-hotspot";
                        }

                        @Override
                        public String name() {
                            return typePrefix + "-hotspot-" + configString + "-" + optionsToString(hotspotOptions) + (asyncProfilerConfiguration.enabled() ? "-async-profiler" : "");
                        }

                        private String combinedOptions() {
                            return hotspotConfiguration.commonOptions() + " " + hotspotOptions;
                        }

                        @Override
                        public Object parameters() {
                            return new HotspotParameters(compileConfiguration, combinedOptions());
                        }

                        record HotspotParameters(@JsonUnwrapped Object compileConfiguration, String hotspotOptions) {}

                        @Override
                        public void setupAndRun(ClientSession benchmarkServerClient, Path outputDirectory, OutputListener.Write log, BenchmarkClosure benchmarkClosure, PhaseTracker.PhaseUpdater progress) throws Exception {
                            progress.update(BenchmarkPhase.INSTALLING_SOFTWARE);
                            SshUtil.run(benchmarkServerClient, "sudo yum install jdk-" + hotspotConfiguration.version() + "-headless -y", log, 0, 1);
                            progress.update(BenchmarkPhase.DEPLOYING_SERVER);
                            ScpClientCreator.instance().createScpClient(benchmarkServerClient)
                                    .upload(shadowJar, SHADOW_JAR_LOCATION);
                            String start = perfStatConfiguration.asCommandPrefix() + "java ";
                            if (asyncProfilerConfiguration.enabled()) {
                                SshUtil.run(benchmarkServerClient, "sudo sysctl kernel.perf_event_paranoid=1", log);
                                SshUtil.run(benchmarkServerClient, "sudo sysctl kernel.kptr_restrict=0", log);
                                ScpClientCreator.instance().createScpClient(benchmarkServerClient)
                                        .upload(asyncProfilerConfiguration.path(), PROFILER_LOCATION);
                                start += "-agentpath:" + PROFILER_LOCATION + "=" + asyncProfilerConfiguration.args() + " ";
                            }
                            LOG.info("Starting benchmark server (hotspot, " + typePrefix + ")");
                            try (ChannelExec cmd = benchmarkServerClient.createExecChannel(start + combinedOptions() + " -jar " + SHADOW_JAR_LOCATION + (args == null ? "" : " " + args))) {
                                OutputListener.Waiter waiter = new OutputListener.Waiter(ByteBuffer.wrap(boundLine));
                                SshUtil.forwardOutput(cmd, log, waiter);
                                cmd.open().verify();
                                waiter.awaitWithNextPattern(null);

                                try {
                                    benchmarkClosure.benchmark(progress);
                                } finally {
                                    SshUtil.interrupt(cmd);
                                    TimeUnit.SECONDS.sleep(1);
                                    SshUtil.signal(cmd, "KILL");
                                }
                            }
                            if (asyncProfilerConfiguration.enabled()) {
                                LOG.info("Downloading async-profiler results");
                                for (String output : asyncProfilerConfiguration.outputs()) {
                                    ScpClientCreator.instance().createScpClient(benchmarkServerClient)
                                            .download(output, outputDirectory.resolve(output));
                                }
                            }
                        }
                    }),
                    // one run for each native-image option choice to test
                    nativeImageConfiguration.optionChoices().stream().map(nativeImageOptions -> new FrameworkRun() {
                        @Override
                        public String type() {
                            return typePrefix + "-native";
                        }

                        @Override
                        public String name() {
                            return typePrefix + "-native-" + configString + "-" + optionsToString(nativeImageOptions);
                        }

                        @Override
                        public Object parameters() {
                            return new NativeImageParameters(compileConfiguration, nativeImageOptions);
                        }

                        record NativeImageParameters(@JsonUnwrapped Object compileConfiguration, String nativeImageOptions) {}

                        @Override
                        public void setupAndRun(ClientSession benchmarkServerClient, Path outputDirectory, OutputListener.Write log, BenchmarkClosure benchmarkClosure, PhaseTracker.PhaseUpdater progress) throws Exception {

                            progress.update(BenchmarkPhase.INSTALLING_SOFTWARE);
                            SshUtil.run(benchmarkServerClient, "sudo yum install graalvm-" + nativeImageConfiguration.version() + "-jdk -y", log, 0, 1);
                            SshUtil.run(benchmarkServerClient, "sudo yum update oraclelinux-release-el9 -y", log, 0, 1);
                            SshUtil.run(benchmarkServerClient, "sudo yum config-manager --set-enabled ol9_codeready_builder", log, 0, 1);
                            SshUtil.run(benchmarkServerClient, "sudo yum install graalvm-" + nativeImageConfiguration.version() + "-native-image -y", log, 0, 1);
                            progress.update(BenchmarkPhase.DEPLOYING_SERVER);
                            ScpClientCreator.instance().createScpClient(benchmarkServerClient)
                                    .upload(shadowJar, SHADOW_JAR_LOCATION);
                            progress.update(BenchmarkPhase.BUILDING_PGO_IMAGE);
                            String niCommandBase = "native-image --no-fallback " + nativeImageOptions + " " + additionalNativeImageOptions;
                            SshUtil.run(benchmarkServerClient, niCommandBase + " --pgo-instrument -jar " + SHADOW_JAR_LOCATION + " pgo-instrument", log);
                            LOG.info("Starting benchmark server for PGO (native, micronaut)");
                            try (ChannelExec cmd = benchmarkServerClient.createExecChannel(perfStatConfiguration.asCommandPrefix() + "./pgo-instrument" + (args == null ? "" : " " + args))) {
                                OutputListener.Waiter waiter = new OutputListener.Waiter(ByteBuffer.wrap(boundLine));
                                SshUtil.forwardOutput(cmd, log, waiter);
                                cmd.open().verify();
                                waiter.awaitWithNextPattern(null);

                                try {
                                    benchmarkClosure.pgoLoad(progress);
                                } finally {
                                    SshUtil.interrupt(cmd);
                                    SshUtil.joinAndCheck(cmd, 130);
                                }
                            }
                            progress.update(BenchmarkPhase.BUILDING_IMAGE);
                            SshUtil.run(benchmarkServerClient, niCommandBase + " --pgo -jar " + SHADOW_JAR_LOCATION + " optimized", log);
                            LOG.info("Starting benchmark server (native, " + typePrefix + ")");
                            try (ChannelExec cmd = benchmarkServerClient.createExecChannel(perfStatConfiguration.asCommandPrefix() + "./optimized" + (args == null ? "" : " " + args))) {
                                OutputListener.Waiter waiter = new OutputListener.Waiter(ByteBuffer.wrap(boundLine));
                                SshUtil.forwardOutput(cmd, log, waiter);
                                cmd.open().verify();
                                waiter.awaitWithNextPattern(null);

                                try {
                                    benchmarkClosure.benchmark(progress);
                                } finally {
                                    SshUtil.interrupt(cmd);
                                    SshUtil.joinAndCheck(cmd, 130);
                                }
                            }
                        }
                    })
            );
        }
    }
}
