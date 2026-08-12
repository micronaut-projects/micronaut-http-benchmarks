package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import jakarta.inject.Singleton;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        process.getInputStream().transferTo(new OutputListener.Stream(List.of(log)));
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
        process.getErrorStream().transferTo(new OutputListener.Stream(List.of(log)));
        byte[] bytes = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Nix exit with next output: " + exit);
        }
        return jsonMapper.readTree(bytes);
    }

    public Path build(OutputListener log, String installable) throws Exception {
        JsonNode answer = nixJson(log, List.of(
                "build", installable,
                "--json", "--no-link"
        ));
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
        return "set -e\n"
                + "deadline=$((SECONDS + 840))\n"
                + "while ! " + NIX_REMOTE + " copy --no-check-sigs --from " + cacheUri + " " + derivation + "; do\n"
                + "  if [ \"$SECONDS\" -ge \"$deadline\" ]; then exit 1; fi\n"
                + "  sleep 5\n"
                + "done\n"
                + "profile=$(" + NIX_REMOTE + " build --no-link --print-out-paths " + derivation + "^out)\n"
                + "$profile/bin/switch-to-configuration switch\n";
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
}
