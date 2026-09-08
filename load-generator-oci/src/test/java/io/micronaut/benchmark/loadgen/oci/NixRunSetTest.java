package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.benchmark.loadgen.oci.cmd.PortForwardHandle;
import io.micronaut.benchmark.loadgen.oci.cmd.ProcessBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NixRunSetTest {
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    @TempDir
    Path temporaryDirectory;

    @Test
    void mapsLegacyTwoFieldProfilingMetadata() {
        FrameworkRun run = runFor("""
                {"tool":"perf","artifact":"profile.data"}
                """);

        assertEquals("profile.data", run.profiling().artifact());
        assertNull(run.profiling().injectedArtifact());
        assertNull(run.profiling().symbolDirectory());
    }

    @Test
    void collectsEveryDeclaredProfilingArtifactAfterStoppingTheService() throws Exception {
        Path remoteRoot = temporaryDirectory.resolve("remote");
        Files.createDirectories(remoteRoot.resolve("profile-symbols/subdirectory"));
        Files.writeString(remoteRoot.resolve("profile.data"), "raw");
        Files.writeString(remoteRoot.resolve("profile.jit.data"), "injected");
        Files.writeString(remoteRoot.resolve("profile-symbols/symbol.debug"), "symbol");
        Files.writeString(remoteRoot.resolve("profile-symbols/subdirectory/index"), "index");

        Path outputDirectory = temporaryDirectory.resolve("output");
        Files.createDirectories(outputDirectory.resolve("profile-symbols"));
        Files.writeString(outputDirectory.resolve("profile-symbols/stale.debug"), "stale");
        LocalArtifactCommandRunner commandRunner = new LocalArtifactCommandRunner(remoteRoot);

        FrameworkRun run = runFor("""
                {
                  "tool":"perf",
                  "artifact":"profile.data",
                  "injectedArtifact":"profile.jit.data",
                  "symbolDirectory":"profile-symbols"
                }
                """);
        execute(run, commandRunner, outputDirectory);

        assertEquals("raw", Files.readString(outputDirectory.resolve("profile.data")));
        assertEquals("injected", Files.readString(outputDirectory.resolve("profile.jit.data")));
        assertEquals("symbol", Files.readString(outputDirectory.resolve("profile-symbols/symbol.debug")));
        assertEquals("index", Files.readString(outputDirectory.resolve("profile-symbols/subdirectory/index")));
        assertFalse(Files.exists(outputDirectory.resolve("profile-symbols/stale.debug")));
    }

    @Test
    void doesNotDownloadUndeclaredSupplementalArtifacts() throws Exception {
        Path remoteRoot = temporaryDirectory.resolve("remote");
        Files.createDirectories(remoteRoot);
        Files.writeString(remoteRoot.resolve("profile.data"), "raw");
        Path outputDirectory = temporaryDirectory.resolve("output");
        Files.createDirectories(outputDirectory);
        LocalArtifactCommandRunner commandRunner = new LocalArtifactCommandRunner(remoteRoot);

        execute(runFor("""
                {"tool":"perf","artifact":"profile.data"}
                """), commandRunner, outputDirectory);

        assertEquals("raw", Files.readString(outputDirectory.resolve("profile.data")));
    }

    @Test
    void failedSupplementalDownloadRemovesStaleAndPartiallyCollectedBundle() throws Exception {
        Path remoteRoot = temporaryDirectory.resolve("remote");
        Files.createDirectories(remoteRoot.resolve("profile-symbols"));
        Files.writeString(remoteRoot.resolve("profile.data"), "new raw");
        Files.writeString(remoteRoot.resolve("profile.jit.data"), "new injected");
        Files.writeString(remoteRoot.resolve("profile-symbols/symbol.debug"), "new symbol");

        Path outputDirectory = temporaryDirectory.resolve("output");
        Files.createDirectories(outputDirectory.resolve("profile-symbols"));
        Files.writeString(outputDirectory.resolve("profile.data"), "stale raw");
        Files.writeString(outputDirectory.resolve("profile.jit.data"), "stale injected");
        Files.writeString(outputDirectory.resolve("profile-symbols/stale.debug"), "stale symbol");
        LocalArtifactCommandRunner commandRunner = new LocalArtifactCommandRunner(remoteRoot, true);

        FrameworkRun run = runFor("""
                {
                  "tool":"perf",
                  "artifact":"profile.data",
                  "injectedArtifact":"profile.jit.data",
                  "symbolDirectory":"profile-symbols"
                }
                """);
        assertThrows(IOException.class, () -> execute(run, commandRunner, outputDirectory));

        assertFalse(Files.exists(outputDirectory.resolve("profile.data")));
        assertFalse(Files.exists(outputDirectory.resolve("profile.jit.data")));
        assertFalse(Files.exists(outputDirectory.resolve("profile-symbols")));
        try (var remaining = Files.list(outputDirectory)) {
            assertEquals(List.of(), remaining.toList());
        }
    }

    @Test
    void rejectsEmptyDownloadedArtifactsWithoutPublishingThem() throws Exception {
        Path emptyFileRemote = temporaryDirectory.resolve("empty-file-remote");
        Files.createDirectories(emptyFileRemote);
        Files.createFile(emptyFileRemote.resolve("profile.data"));
        Path emptyFileOutput = temporaryDirectory.resolve("empty-file-output");
        Files.createDirectories(emptyFileOutput);

        assertThrows(IOException.class, () -> execute(runFor("""
                {"tool":"perf","artifact":"profile.data"}
                """), new LocalArtifactCommandRunner(emptyFileRemote), emptyFileOutput));
        try (var remaining = Files.list(emptyFileOutput)) {
            assertEquals(List.of(), remaining.toList());
        }

        Path emptySymbolsRemote = temporaryDirectory.resolve("empty-symbols-remote");
        Files.createDirectories(emptySymbolsRemote.resolve("profile-symbols"));
        Files.writeString(emptySymbolsRemote.resolve("profile.data"), "raw");
        Files.writeString(emptySymbolsRemote.resolve("profile.jit.data"), "injected");
        Path emptySymbolsOutput = temporaryDirectory.resolve("empty-symbols-output");
        Files.createDirectories(emptySymbolsOutput);

        assertThrows(IOException.class, () -> execute(runFor("""
                {
                  "tool":"perf",
                  "artifact":"profile.data",
                  "injectedArtifact":"profile.jit.data",
                  "symbolDirectory":"profile-symbols"
                }
                """), new LocalArtifactCommandRunner(emptySymbolsRemote), emptySymbolsOutput));
        try (var remaining = Files.list(emptySymbolsOutput)) {
            assertEquals(List.of(), remaining.toList());
        }
    }

    private static void execute(FrameworkRun run, CommandRunner commandRunner, Path outputDirectory) throws Exception {
        run.setupAndRun(
                commandRunner,
                outputDirectory,
                new OutputListener.Write(OutputStream.nullOutputStream()),
                noOpBenchmarkClosure(),
                null,
                (phase, percent, displayProgress) -> { }
        );
    }

    private static FrameworkRun runFor(String profilingJson) {
        String metadataJson = """
                {
                  "suites": {
                    "test": {
                      "runs": [{
                        "type": "test",
                        "name": "test",
                        "parameters": {},
                        "nixosConfiguration": "test-configuration",
                        "profiling": %s
                      }],
                      "documents": [],
                      "protocols": {},
                      "statusRequest": {"name":"status","uri":"/status"}
                    }
                  },
                  "instanceTypes": {},
                  "benchmarkDefinitions": "."
                }
                """.formatted(profilingJson);
        BenchmarkMetadata metadata = BenchmarkMetadata.parse(JSON_MAPPER, metadataJson, "test");
        return new NixRunSet(metadata, new Nix(JSON_MAPPER)).getRuns().getFirst();
    }

    private static FrameworkRun.BenchmarkClosure noOpBenchmarkClosure() {
        return new FrameworkRun.BenchmarkClosure() {
            @Override
            public void benchmark(PhaseTracker.PhaseUpdater progress) {
            }

            @Override
            public void pgoLoad(PhaseTracker.PhaseUpdater progress) {
                throw new AssertionError("Unexpected PGO load");
            }
        };
    }

    private static final class LocalArtifactCommandRunner implements CommandRunner {
        private final Path remoteRoot;
        private final boolean failAfterRecursiveDownload;

        private LocalArtifactCommandRunner(Path remoteRoot) {
            this(remoteRoot, false);
        }

        private LocalArtifactCommandRunner(Path remoteRoot, boolean failAfterRecursiveDownload) {
            this.remoteRoot = remoteRoot;
            this.failAfterRecursiveDownload = failAfterRecursiveDownload;
        }

        @Override
        public ProcessBuilder builder(String command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void runAndCheck(String command, OutputListener... log) {
        }

        @Override
        public void upload(byte[] local, String remote, Set<PosixFilePermission> permissions) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] downloadBytes(String path) throws IOException {
            return Files.readAllBytes(remotePath(path));
        }

        @Override
        public void downloadRecursive(String remote, Path local) throws IOException {
            try (var files = Files.walk(remotePath(remote))) {
                for (Path source : files.toList()) {
                    Path target = local.resolve(remotePath(remote).relativize(source).toString());
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            if (failAfterRecursiveDownload) {
                throw new IOException("Simulated recursive download failure");
            }
        }

        private Path remotePath(String remote) {
            return remoteRoot.resolve(Path.of(remote).getFileName().toString());
        }

        @Override
        public PortForwardHandle portForward(InetSocketAddress remoteAddress) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }
}
