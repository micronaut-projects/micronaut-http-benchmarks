package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.benchmark.loadgen.oci.cmd.VanillaSsh;
import jakarta.inject.Singleton;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Singleton
public class Nix {
    private final JsonMapper jsonMapper;

    public Nix(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    private void nix(OutputListener log, List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>(Arrays.asList(
                "/nix/var/nix/profiles/default/bin/nix",
                "--extra-experimental-features", "nix-command",
                "--extra-experimental-features", "flakes"
        ));
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
        List<String> cmd = new ArrayList<>(Arrays.asList(
                "/nix/var/nix/profiles/default/bin/nix",
                "--extra-experimental-features", "nix-command",
                "--extra-experimental-features", "flakes"
        ));
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

    public void anywhere(OutputListener log, VanillaSsh ssh, List<String> authorizedKeys, String flake) throws Exception {
        List<String> args = new ArrayList<>(List.of(
                "nixpkgs#nix-anywhere", "--",
                "--flake", flake,
                "--target-host", ssh.host(),
                "--ssh-port", String.valueOf(ssh.port()),
                "--copy-host-keys",
                "--build-on", "remote",
                "--generate-hardware-config", "nixos-facter", "./build/facter.json"
        ));
        ssh.options().forEach((k, v) -> {
            args.add("--ssh-option");
            args.add(k + "=" + v);
        });

        Files.writeString(Paths.get("nix", "build", "authorized_keys.nix"), authorizedKeys.stream().map(s -> '"' + s + '"').collect(Collectors.joining(" ", "[", "]")));

        nix(log, args);
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
}
