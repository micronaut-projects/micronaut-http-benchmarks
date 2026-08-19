package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import jakarta.inject.Singleton;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Singleton
public class Nix {
    private static final List<String> NIX_LOCAL = List.of(
            "/nix/var/nix/profiles/default/bin/nix",
            "--extra-experimental-features", "nix-command",
            "--extra-experimental-features", "flakes"
    );
    private static final String NIX_REMOTE = "/run/current-system/sw/bin/nix --extra-experimental-features nix-command --extra-experimental-features flakes";

    private final JsonMapper jsonMapper;

    public Nix(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    private void nix(OutputListener log, List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>(NIX_LOCAL);
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(Path.of("nix").toFile());
        pb.command(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (OutputStream stdout = new OutputListener.Stream(List.of(log))) {
            process.getInputStream().transferTo(stdout);
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Nix exit with next output: " + exit);
        }
    }

    private JsonNode nixJson(OutputListener log, List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>(NIX_LOCAL);
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(Path.of("nix").toFile());
        pb.command(cmd);
        Process process = pb.start();
        AtomicReference<IOException> stderrFailure = new AtomicReference<>();
        Thread stderrThread = Thread.ofVirtual().start(() -> {
            try {
                process.getErrorStream().transferTo(new OutputListener.Stream(List.of(log)));
            } catch (IOException e) {
                stderrFailure.set(e);
            }
        });
        byte[] bytes = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        stderrThread.join();
        if (stderrFailure.get() != null) {
            throw stderrFailure.get();
        }
        if (exit != 0) {
            throw new IllegalStateException("Nix exit with next output: " + exit);
        }
        return jsonMapper.readTree(bytes);
    }

    public Path build(OutputListener log, String installable) throws Exception {
        return build(log, installable, List.of());
    }

    public Path build(OutputListener log, String installable, List<String> extraArgs) throws Exception {
        List<String> args = new ArrayList<>();
        args.add("build");
        args.add(installable);
        args.addAll(extraArgs);
        args.addAll(List.of("--json", "--no-link"));
        JsonNode answer = nixJson(log, args);
        Path path = Path.of(answer.get(0).get("outputs").get("out").stringValue());
        if (!path.startsWith(Path.of("/nix/store"))) {
            throw new IllegalStateException("Weird result path");
        }
        return path;
    }

    public byte[] buildBenchmarkMetadata(OutputListener log) throws Exception {
        return Files.readAllBytes(build(log, ".#benchmark-metadata"));
    }

    public static String activate(URI cacheUri, String derivation) {
        String cache = shellQuote(cacheUri.toString());
        String quotedDerivation = shellQuote(derivation);
        String output = shellQuote(derivation + "^out");
        StringBuilder command = new StringBuilder("set -e\n")
                .append("deadline=$((SECONDS + 840))\n")
                .append("activation_start=$SECONDS\n")
                .append("printf '%s %ss %s\\n' \"$(date -Is)\" \"$SECONDS\" 'nix cache copy start'\n")
                .append("while ! ").append(NIX_REMOTE).append(" copy --no-check-sigs --from ").append(cache).append(' ').append(quotedDerivation).append("; do\n")
                .append("  if [ \"$SECONDS\" -ge \"$deadline\" ]; then exit 1; fi\n")
                .append("  printf '%s %ss %s\\n' \"$(date -Is)\" \"$SECONDS\" 'nix cache copy retry'\n")
                .append("  sleep 5\n")
                .append("done\n")
                .append("printf '%s %ss %ss %s\\n' \"$(date -Is)\" \"$SECONDS\" \"$((SECONDS - activation_start))\" 'nix cache copy complete'\n");
        command.append("profile_start=$SECONDS\n")
                .append("printf '%s %ss %s\\n' \"$(date -Is)\" \"$SECONDS\" 'nix profile realization start'\n")
                .append("profile=$(").append(NIX_REMOTE).append(" build --no-link --print-out-paths");
        command.append(" --option extra-substituters ").append(cache);
        return command.append(' ').append(output).append(")\n")
                .append("printf '%s %ss %ss %s\\n' \"$(date -Is)\" \"$SECONDS\" \"$((SECONDS - profile_start))\" 'nix profile realization complete'\n")
                .append("switch_start=$SECONDS\n")
                .append("printf '%s %ss %s\\n' \"$(date -Is)\" \"$SECONDS\" 'nix activation start'\n")
                .append("$profile/bin/switch-to-configuration switch\n")
                .append("printf '%s %ss %ss %s\\n' \"$(date -Is)\" \"$SECONDS\" \"$((SECONDS - switch_start))\" 'nix activation complete'\n")
                .toString();
    }

    public static String prefetch(List<NixCacheAccess> resources) {
        StringBuilder command = new StringBuilder("set -e\n")
                .append("prefetch_start=$SECONDS\n")
                .append("printf '%s %ss %s\\n' \"$(date -Is)\" \"$SECONDS\" 'nix prefetch start'\n");
        for (NixCacheAccess resource : resources) {
            String derivation = shellQuote(resource.defaultDerivation());
            command.append("closure_start=$SECONDS\n")
                    .append("deadline=$((SECONDS + 840))\n")
                    .append("printf '%s %ss %s %s\\n' \"$(date -Is)\" \"$SECONDS\" 'nix prefetch closure start' ").append(derivation).append("\n")
                    .append("while ! ").append(NIX_REMOTE).append(" copy --no-check-sigs --from ")
                    .append(shellQuote(resource.readUri().toString())).append(' ').append(derivation).append("; do\n")
                    .append("  if [ \"$SECONDS\" -ge \"$deadline\" ]; then exit 1; fi\n")
                    .append("  printf '%s %ss %s %s\\n' \"$(date -Is)\" \"$SECONDS\" 'nix prefetch closure retry' ").append(derivation).append("\n")
                    .append("  sleep 5\n")
                    .append("done\n")
                    .append("printf '%s %ss %ss %s %s\\n' \"$(date -Is)\" \"$SECONDS\" \"$((SECONDS - closure_start))\" 'nix prefetch closure complete' ").append(derivation).append("\n");
        }
        return command.append("printf '%s %ss %ss %s\\n' \"$(date -Is)\" \"$SECONDS\" \"$((SECONDS - prefetch_start))\" 'nix prefetch complete'\n")
                .toString();
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /**
     * Get the nix-store path name of a derivation.
     *
     * @param installable The installable, e.g. {@code .#packages.x86_64-linux.relay-server-system}
     * @return The nix-store path name, e.g. {@code /nix/store/kzzvpzy4qg13w6iqbxgaj8d0qps547la-nixos-system-nixos-oci-26.05.20260803.531670d.drv}
     */
    public String getDerivation(OutputListener log, String installable) throws Exception {
        return nixJson(log, List.of("path-info", "--json", "--json-format", "1", "--derivation", installable)).propertyNames().iterator().next();
    }

    public void buildAndUploadCache(OutputListener log, URI cache, String installable) throws Exception {
        uploadCache(log, cache, build(log, installable).toString(), false);
    }

    public void uploadCache(OutputListener log, URI cache, String installable, boolean derivation) throws Exception {
        List<String> args = new ArrayList<>();
        args.add("copy");
        if (derivation) {
            args.add("--derivation");
        }
        args.add("--to");
        args.add(cache + "?compression=zstd");
        args.add(installable);

        nix(log, args);
    }

    public Path addStorePath(OutputListener log, Path localDirectory) throws Exception {
        if (!Files.isDirectory(localDirectory)) {
            throw new IllegalArgumentException("PGO path is not a directory: " + localDirectory);
        }
        return nixStoreAdd(log, List.of("store", "add", localDirectory.toAbsolutePath().normalize().toString()));
    }

    public String evaluatePgoDerivation(OutputListener log, String optimizedConfiguration, Path pgoStorePath) throws Exception {
        Path validatedPgoStorePath = storePath(pgoStorePath.toString(), false);
        JsonNode value = nixJson(log, List.of("eval", "--json", "--impure", "--expr",
                "(let flake = builtins.getFlake \"path:${toString ../.}?dir=nix\"; in flake.lib.pgoToplevel "
                        + nixString(optimizedConfiguration) + " (builtins.storePath " + nixString(validatedPgoStorePath.toString()) + ")).drvPath"));
        return storePath(value.stringValue(), true).toString();
    }

    public void uploadPgoCache(OutputListener log, URI cache, Path pgoStorePath, String derivationPath) throws Exception {
        uploadCache(log, cache, storePath(pgoStorePath.toString(), false).toString(), false);
        uploadCache(log, cache, storePath(derivationPath, true).toString(), true);
    }

    private Path nixStoreAdd(OutputListener log, List<String> args) throws Exception {
        List<String> command = new ArrayList<>(NIX_LOCAL);
        command.addAll(args);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(Path.of("nix").toFile());
        Process process = processBuilder.start();
        AtomicReference<IOException> stderrFailure = new AtomicReference<>();
        Thread stderrThread = Thread.ofVirtual().start(() -> {
            try (OutputStream stderr = new OutputListener.Stream(List.of(log))) {
                process.getErrorStream().transferTo(stderr);
            } catch (IOException e) {
                stderrFailure.set(e);
            }
        });
        byte[] output = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        stderrThread.join();
        if (stderrFailure.get() != null) {
            throw stderrFailure.get();
        }
        if (exit != 0) {
            throw new IllegalStateException("Nix exit with next output: " + exit);
        }
        String storePath = new String(output, java.nio.charset.StandardCharsets.UTF_8).strip();
        if (storePath.lines().count() != 1) {
            throw new IllegalStateException("Malformed Nix store path: " + storePath);
        }
        return storePath(storePath, false);
    }

    private static Path storePath(String value, boolean derivation) {
        Path path = Path.of(value);
        Path store = Path.of("/nix/store");
        if (!path.startsWith(store) || path.getNameCount() != store.getNameCount() + 1 || derivation != path.getFileName().toString().endsWith(".drv")) {
            throw new IllegalStateException("Malformed Nix store path: " + value);
        }
        return path;
    }

    private static String nixString(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("${", "\\${") + '"';
    }
}
