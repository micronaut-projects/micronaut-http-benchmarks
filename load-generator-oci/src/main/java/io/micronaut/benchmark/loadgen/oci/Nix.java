package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.benchmark.loadgen.oci.cmd.VanillaSsh;
import jakarta.inject.Singleton;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Singleton
public class Nix {
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
}
