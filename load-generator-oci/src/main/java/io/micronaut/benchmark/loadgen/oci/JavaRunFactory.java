package io.micronaut.benchmark.loadgen.oci;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.scp.client.ScpClient;
import org.apache.sshd.scp.client.ScpClientCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipOutputStream;

/**
 * Factory for {@link FrameworkRun}s that use a {@code java} command to run a shadow jar. This class handles things
 * like JVM flags and async-profiler setup. It can also run the jar using native-image.
 */
@Singleton
public final class JavaRunFactory {
    private static final Logger LOG = LoggerFactory.getLogger(JavaRunFactory.class);
    private static final String SHADOW_JAR_LOCATION = "shadow.jar";
    private static final String CLASSPATH_LOCATION = "classpath";

    private final HotspotConfiguration hotspotConfiguration;
    private final NativeImageConfiguration nativeImageConfiguration;
    private final AsyncProfilerHelper.AsyncProfilerConfiguration asyncProfilerConfiguration;
    private final AsyncProfilerHelper asyncProfilerHelper;
    private final PerfStatConfiguration perfStatConfiguration;

    public JavaRunFactory(HotspotConfiguration hotspotConfiguration, NativeImageConfiguration nativeImageConfiguration, AsyncProfilerHelper.AsyncProfilerConfiguration asyncProfilerConfiguration, AsyncProfilerHelper asyncProfilerHelper, PerfStatConfiguration perfStatConfiguration) {
        this.hotspotConfiguration = hotspotConfiguration;
        this.nativeImageConfiguration = nativeImageConfiguration;
        this.asyncProfilerConfiguration = asyncProfilerConfiguration;
        this.asyncProfilerHelper = asyncProfilerHelper;
        this.perfStatConfiguration = perfStatConfiguration;
    }

    private static String optionsToString(Map.Entry<String, String> opts) {
        return opts.getKey();
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
        private Collection<Path> classpath;
        private String mainClass;
        private String additionalJvmArgs;
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
            this.shadowJar = shadowJar;
            this.classpath = null;
            this.mainClass = null;
            return this;
        }

        /**
         * Classpath and main class to run.
         */
        public RunBuilder classPath(Collection<Path> items, String mainClass) {
            for (Path item : items) {
                if (!Files.exists(item)) {
                    throw new IllegalArgumentException("File does not exist: " + item);
                }
            }
            this.classpath = items;
            this.mainClass = mainClass;
            this.shadowJar = null;
            return this;
        }

        public RunBuilder additionalJvmArgs(String additionalJvmArgs) {
            this.additionalJvmArgs = additionalJvmArgs;
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

        private void uploadClasspath(ClientSession benchmarkServerClient, OutputListener.Write log) throws IOException {
            ScpClient scpClient = ScpClientCreator.instance().createScpClient(benchmarkServerClient);
            if (shadowJar != null) {
                scpClient.upload(shadowJar, SHADOW_JAR_LOCATION);
            } else {
                Path tmp = Files.createTempFile("micronaut-benchmark-JavaRunFactory-classpath", ".zip");
                try {
                    log.println("Zipping classpath");
                    new ZipOutputStream(Files.newOutputStream(tmp)).close();
                    try (FileSystem fs = FileSystems.newFileSystem(tmp)) {
                        Path dest = fs.getPath(CLASSPATH_LOCATION);
                        Files.createDirectory(dest);
                        for (Path path : classpath) {
                            Path d = dest.resolve(path.getFileName().toString());
                            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                                @Override
                                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                                    Files.createDirectory(d.resolve(path.relativize(dir).toString()));
                                    return FileVisitResult.CONTINUE;
                                }

                                @Override
                                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                    Files.copy(file, d.resolve(path.relativize(file).toString()));
                                    return FileVisitResult.CONTINUE;
                                }
                            });
                        }
                    }
                    log.println("Uploading classpath");
                    scpClient.upload(tmp, "classpath.zip");
                    SshUtil.run(benchmarkServerClient, "rm -rf " + CLASSPATH_LOCATION, log);
                    SshUtil.run(benchmarkServerClient, "unzip classpath.zip", log);
                } finally {
                    Files.delete(tmp);
                }
            }
        }

        private String jarArgument() {
            if (shadowJar != null) {
                return "-jar " + SHADOW_JAR_LOCATION;
            } else {
                return "-cp " +
                        classpath.stream()
                                .map(p -> CLASSPATH_LOCATION + "/" + p.getFileName())
                                .collect(Collectors.joining(":"))
                        + " " + mainClass;
            }
        }

        /**
         * Build the runs for this jar. This returns multiple runs if multiple different JVM flag choices are
         * configured, and for native-image.
         */
        public Stream<FrameworkRun> build() {
            return Stream.concat(
                    // one run for each JVM option choice to test
                    hotspotConfiguration.optionChoices().entrySet().stream().map(hotspotOptions -> new FrameworkRun() {
                        @Override
                        public String type() {
                            return typePrefix + "-hotspot";
                        }

                        @Override
                        public String name() {
                            return typePrefix + "-hotspot-" + configString + "-" + optionsToString(hotspotOptions) + (asyncProfilerConfiguration.enabled() ? "-async-profiler" : "");
                        }

                        private String combinedOptions() {
                            return hotspotConfiguration.commonOptions() + " " + hotspotOptions.getValue();
                        }

                        @Override
                        public Object parameters() {
                            return new HotspotParameters(compileConfiguration, combinedOptions());
                        }

                        record HotspotParameters(@JsonUnwrapped Object compileConfiguration, String hotspotOptions) {}

                        @Override
                        public void setupAndRun(ClientSession benchmarkServerClient, Path outputDirectory, OutputListener.Write log, BenchmarkClosure benchmarkClosure, PhaseTracker.PhaseUpdater progress) throws Exception {
                            progress.update(BenchmarkPhase.INSTALLING_SOFTWARE);
                            SshUtil.run(benchmarkServerClient, "sudo yum install jdk-" + hotspotConfiguration.version() + "-headful -y", log, 0, 1);
                            SshUtil.run(benchmarkServerClient, "sudo sysctl kernel.yama.ptrace_scope=1", log);
                            progress.update(BenchmarkPhase.DEPLOYING_SERVER);
                            uploadClasspath(benchmarkServerClient, log);
                            String start = perfStatConfiguration.asCommandPrefix() + "java ";
                            if (asyncProfilerConfiguration.enabled()) {
                                asyncProfilerHelper.initialize(benchmarkServerClient, log);
                                start += asyncProfilerHelper.getJvmArgument() + " ";
                            }
                            LOG.info("Starting benchmark server (hotspot, " + typePrefix + ")");
                            String c = start + combinedOptions() + (additionalJvmArgs == null ? "" : " " + additionalJvmArgs) + " " + jarArgument() + (args == null ? "" : " " + args);
                            log.println("$ " + c);
                            try (ChannelExec cmd = benchmarkServerClient.createExecChannel(c)) {
                                OutputListener.Waiter waiter = new OutputListener.Waiter(ByteBuffer.wrap(boundLine));
                                SshUtil.forwardOutput(cmd, log, waiter);
                                cmd.open().verify();
                                waiter.awaitWithNextPattern(null);

                                try {
                                    benchmarkClosure.benchmark(progress);
                                } finally {
                                    SshUtil.signal(cmd, "INT");
                                    if (cmd.waitFor(ClientSession.REMOTE_COMMAND_WAIT_EVENTS, Duration.ofMinutes(1)).contains(ClientChannelEvent.TIMEOUT)) {
                                        LOG.warn("Timeout waiting for process to terminate status={} {}", cmd.getChannelState(), benchmarkServerClient.getSessionState());
                                        SshUtil.run(benchmarkServerClient, "ps -aux", log);
                                        SshUtil.run(benchmarkServerClient, "sudo jhsdb jstack --pid $(pgrep java)", log);
                                        SshUtil.signal(cmd, "KILL");
                                    }
                                }
                            } finally {
                                if (asyncProfilerConfiguration.enabled()) {
                                    asyncProfilerHelper.finish(benchmarkServerClient, log, outputDirectory);
                                }
                            }
                        }
                    }),
                    // one run for each native-image option choice to test
                    nativeImageConfiguration.optionChoices().entrySet().stream().map(nativeImageOptions -> new FrameworkRun() {
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
                            return new NativeImageParameters(compileConfiguration, nativeImageOptions.getValue());
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
                            uploadClasspath(benchmarkServerClient, log);
                            progress.update(BenchmarkPhase.BUILDING_PGO_IMAGE);
                            String niCommandBase = "native-image --no-fallback " + nativeImageOptions.getValue() + " " + additionalNativeImageOptions + (additionalJvmArgs == null ? "" : " " + additionalJvmArgs);
                            SshUtil.run(benchmarkServerClient, niCommandBase + " --pgo-instrument " + jarArgument() + " pgo-instrument", log);
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
                            SshUtil.run(benchmarkServerClient, niCommandBase + " --pgo " + jarArgument() + " optimized", log);
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
