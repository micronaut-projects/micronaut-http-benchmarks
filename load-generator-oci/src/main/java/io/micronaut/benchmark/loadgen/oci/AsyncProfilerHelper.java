package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import one.convert.Arguments;
import one.convert.FlameGraph;
import one.convert.JfrToFlame;
import one.convert.JfrToHeatmap;
import one.convert.JfrToPprof;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.scp.client.ScpClientCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Singleton
public class AsyncProfilerHelper {
    private static final Logger LOG = LoggerFactory.getLogger(AsyncProfilerHelper.class);
    private static final String PROFILER_LOCATION = "/tmp/libasyncProfiler.so";
    private static final Map<String, URL> PROFILER_CLASSPATH_LOCATIONS = Map.of(
            "x86_64", JavaRunFactory.class.getResource("/linux-x64/libasyncProfiler.so"),
            "aarch64", JavaRunFactory.class.getResource("/linux-arm64/libasyncProfiler.so")
    );
    private static final String JFR_CONFIG_LOCATION = "/tmp/config.jfc";

    private final AsyncProfilerConfiguration configuration;

    public AsyncProfilerHelper(AsyncProfilerConfiguration configuration) {
        this.configuration = configuration;
    }

    public void initialize(ClientSession ssh, OutputListener.Write log) throws Exception {
        SshUtil.run(ssh, "sudo sysctl kernel.perf_event_paranoid=1", log);
        SshUtil.run(ssh, "sudo sysctl kernel.kptr_restrict=0", log);
        String arch;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            SshUtil.run(ssh, "uname -m", log, new OutputListener.Write(out));
            arch = out.toString(StandardCharsets.UTF_8).trim();
        }
        URL localUrl = PROFILER_CLASSPATH_LOCATIONS.get(arch);
        if (localUrl == null) {
            throw new IllegalStateException("Architecture not supported: " + arch);
        }
        byte[] bytes;
        try (InputStream input = localUrl.openStream()) {
            bytes = input.readAllBytes();
        }
        ScpClientCreator.instance().createScpClient(ssh)
                .upload(bytes, PROFILER_LOCATION, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE
                ), null);
        if (configuration.jfrConfig != null) {
            SshUtil.run(ssh, "jfr configure --input default.jfc " + configuration.jfrConfig + " --output " + JFR_CONFIG_LOCATION, log);
        }
    }

    public String getJvmArgument() {
        String s = "-agentpath:" + PROFILER_LOCATION + "=" + configuration.args();
        if (configuration.jfrConfig != null) {
            s += ",jfrsync=" + JFR_CONFIG_LOCATION;
        }
        return s;
    }

    public void finish(ClientSession ssh, OutputListener.Write log, Path outputDirectory) throws Exception {
        LOG.info("Downloading async-profiler results");
        for (String output : configuration.outputs()) {
            Path local = outputDirectory.resolve(output);
            ScpClientCreator.instance().createScpClient(ssh)
                    .download(output, local);
            if (Files.size(local) == 0) {
                LOG.warn("Async-profiler result {} was empty, check log for directory listing", output);
                SshUtil.run(ssh, "ls -lah", log);
            }
        }
        for (AsyncProfilerConversion conversion : configuration.conversions()) {
            Path input = outputDirectory.resolve(conversion.input());
            if (!Files.exists(input) || Files.size(input) == 0) {
                LOG.warn("Skipping async-profiler conversion to {} because input file {} does not exist or is empty", conversion.output(), conversion.input());
                continue;
            }
            try {
                convert(
                        input.toString(),
                        outputDirectory.resolve(conversion.output()).toString(),
                        new Arguments(conversion.args().split(" "))
                );
            } catch (Exception e) {
                LOG.warn("Failed to convert {} to {}", conversion.input(), conversion.output(), e);
            }
        }
    }

    // stolen from jfrconv Main
    private static void convert(String input, String output, Arguments args) throws IOException {
        if (isJfr(input)) {
            if ("html".equals(args.output) || "collapsed".equals(args.output)) {
                JfrToFlame.convert(input, output, args);
            } else if ("pprof".equals(args.output) || "pb".equals(args.output) || args.output.endsWith("gz")) {
                JfrToPprof.convert(input, output, args);
            } else if ("heatmap".equals(args.output)) {
                JfrToHeatmap.convert(input, output, args);
            } else {
                throw new IllegalArgumentException("Unrecognized output format: " + args.output);
            }
        } else {
            FlameGraph.convert(input, output, args);
        }
    }

    // stolen from jfrconv Main
    private static boolean isJfr(String fileName) throws IOException {
        if (fileName.endsWith(".jfr")) {
            return true;
        } else if (fileName.endsWith(".collapsed") || fileName.endsWith(".txt") || fileName.endsWith(".csv")) {
            return false;
        }
        byte[] buf = new byte[4];
        try (FileInputStream fis = new FileInputStream(fileName)) {
            return fis.read(buf) == 4 && buf[0] == 'F' && buf[1] == 'L' && buf[2] == 'R' && buf[3] == 0;
        }
    }

    /**
     * Configuration for async-profiler support.
     *
     * @param enabled Whether async-profiler should be enabled. This may have an impact on performance, so don't do this in
     *                a measurement benchmark.
     * @param args    Args to pass to async-profiler. e.g. {@code start,event=cpu,file=flamegraph.html}
     * @param outputs Output files to copy from the server-under-test VM after profiling is done, e.g.
     *                {@code flamegraph.html}
     */
    @ConfigurationProperties("variants.hotspot.async-profiler")
    public record AsyncProfilerConfiguration(
            boolean enabled,
            String args,
            @Nullable String jfrConfig,
            List<String> outputs,
            List<AsyncProfilerConversion> conversions
    ) {
    }

    @EachProperty(value = "variants.hotspot.async-profiler.conversion", list = true)
    public record AsyncProfilerConversion(
            String input,
            String output,
            String args
    ) {
    }
}
