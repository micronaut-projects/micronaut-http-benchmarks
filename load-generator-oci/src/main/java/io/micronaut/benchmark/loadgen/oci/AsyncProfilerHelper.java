package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.EachProperty;
import jakarta.inject.Singleton;
import one.convert.Arguments;
import one.convert.FlameGraph;
import one.convert.JfrToFlame;
import one.convert.JfrToHeatmap;
import one.convert.JfrToPprof;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Singleton
public final class AsyncProfilerHelper {
    public static final String PROFILE_FILE_NAME = "profile.jfr";
    public static final String REMOTE_PROFILE_PATH = "/var/lib/sut/" + PROFILE_FILE_NAME;
    private static final Logger LOG = LoggerFactory.getLogger(AsyncProfilerHelper.class);

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

    private static boolean isJfr(String fileName) throws IOException {
        if (fileName.endsWith(".jfr")) {
            return true;
        }
        if (fileName.endsWith(".collapsed") || fileName.endsWith(".txt") || fileName.endsWith(".csv")) {
            return false;
        }
        byte[] buffer = new byte[4];
        try (FileInputStream input = new FileInputStream(fileName)) {
            return input.read(buffer) == 4 && buffer[0] == 'F' && buffer[1] == 'L' && buffer[2] == 'R' && buffer[3] == 0;
        }
    }

    public void convert(Path outputDirectory) {
        for (AsyncProfilerConversion conversion : conversions) {
            Path input = outputDirectory.resolve(conversion.input());
            if (!Files.exists(input)) {
                LOG.warn("Skipping async-profiler conversion to {} because input file {} does not exist", conversion.output(), conversion.input());
                continue;
            }
            try {
                if (Files.size(input) == 0) {
                    LOG.warn("Skipping async-profiler conversion to {} because input file {} is empty", conversion.output(), conversion.input());
                    continue;
                }
                convert(input.toString(), outputDirectory.resolve(conversion.output()).toString(), new Arguments(conversion.args().split(" ")));
            } catch (Exception e) {
                LOG.warn("Failed to convert {} to {}", conversion.input(), conversion.output(), e);
            }
        }
    }

    private final List<AsyncProfilerConversion> conversions;

    public AsyncProfilerHelper(List<AsyncProfilerConversion> conversions) {
        this.conversions = List.copyOf(conversions);
    }

    @EachProperty(value = "async-profiler.conversion", list = true)
    public record AsyncProfilerConversion(String input, String output, String args) {
    }
}
