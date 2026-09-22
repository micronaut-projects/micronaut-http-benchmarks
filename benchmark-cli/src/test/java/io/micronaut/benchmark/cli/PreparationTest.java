package io.micronaut.benchmark.cli;

import io.micronaut.benchmark.api.Nix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PreparationTest {
    @TempDir
    Path temporary;
    private final Nix nix = new Nix(JsonMapper.builder().build());

    @Test
    void externalWorktreesAndInputOverridesResolveIndependentImmutableDerivations() throws Exception {
        String projectFlake = nix.expressionString(Path.of("../nix").toAbsolutePath().normalize().toString());
        String nixpkgs = nix.capture(List.of("eval", "--impure", "--raw", "--expr",
                "(builtins.getFlake " + projectFlake + ").inputs.nixpkgs.outPath"), System.err);
        String system = nix.capture(List.of("eval", "--impure", "--raw", "--expr", "builtins.currentSystem"), System.err);
        Path original = Files.createDirectory(temporary.resolve("original"));
        Path other = temporary.resolve("other-worktree");
        Path input = Files.createDirectory(temporary.resolve("input"));
        Path override = Files.createDirectory(temporary.resolve("override"));
        Files.writeString(input.resolve("value"), "original input\n");
        Files.writeString(override.resolve("value"), "overridden input\n");
        Files.writeString(original.resolve("value"), "committed\n");
        Files.writeString(original.resolve("flake.nix"), """
                {
                  inputs.nixpkgs.url = %s;
                  inputs.data = { url = %s; flake = false; };
                  outputs = { self, nixpkgs, data }: let pkgs = import nixpkgs { system = %s; }; in {
                    lib.catalog = { value = builtins.readFile ./value; };
                    lib.mkExperiment = _: pkgs.runCommand "worktree-experiment" {
                      source = ./value;
                      input = data;
                    } ''cat "$source" "$input/value" > "$out"'';
                  };
                }
                """.formatted(nix.expressionString("path:" + nixpkgs),
                nix.expressionString("path:" + input), nix.expressionString(system)));
        git(original, "init");
        git(original, "add", "flake.nix", "value");
        git(original, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-m", "Fixture");
        git(original, "worktree", "add", "--detach", other.toString());
        Files.writeString(original.resolve("value"), "first agent\n");
        Files.writeString(other.resolve("value"), "second agent\n");

        var first = new Preparation(nix, original.toString(), Map.of());
        var second = new Preparation(nix, other.toString(), Map.of("data", "path:" + override));
        assertEquals("first agent\n", first.catalog().get("value").stringValue());
        assertEquals("second agent\n", second.catalog().get("value").stringValue());
        var a = first.prepare(Map.of(), temporary.resolve("runs"));
        var b = second.prepare(Map.of(), temporary.resolve("runs"));
        assertNotEquals(a.derivation(), b.derivation());
        Files.writeString(original.resolve("value"), "later edit\n");
        Files.writeString(other.resolve("value"), "another later edit\n");
        Files.writeString(input.resolve("value"), "later input\n");
        Files.writeString(override.resolve("value"), "later override\n");
        Path aOutput = nix.realize(Path.of(a.derivation()), a.output(), temporary.resolve("a"), System.err);
        Path bOutput = nix.realize(Path.of(b.derivation()), b.output(), temporary.resolve("b"), System.err);
        assertEquals("first agent\noriginal input\n", Files.readString(aOutput));
        assertEquals("second agent\noverridden input\n", Files.readString(bOutput));
        assertFalse(Files.exists(original.resolve("flake.lock")));
        assertFalse(Files.exists(other.resolve("flake.lock")));
    }

    private static void git(Path directory, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Nix.run(new ProcessBuilder(command), System.err, System.err);
    }
}
