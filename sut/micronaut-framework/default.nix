{ config, lib, pkgs, ... }:
let
  codec = config.micronaut-framework.codec;
in {
  options.micronaut-framework.codec = lib.mkOption {
    type = lib.types.enum [ "jackson-databind" "micronaut-serialization" ];
    default = "jackson-databind";
    description = "The Micronaut Framework JSON codec used by this benchmark run.";
  };

  config.benchmark = {
    jvm = {
      enable = true;
      extraArgs = [
        "-Djdk.trackAllThreads=false"
        "-XX:+UnlockExperimentalVMOptions"
        "--add-opens=java.base/java.lang=ALL-UNNAMED"
      ];
    };
    sut = {
      package = pkgs.callPackage ./package.nix { inherit codec; };
      executable = "micronaut-framework";
      description = "Micronaut Framework ${codec} benchmark server";
      environment = [ "MICRONAUT_SYSTEMD_NOTIFY_ENABLED=true" ];
      metadata = {
        type = "micronaut-framework-hotspot-${codec}";
        parameters = {
          runtime = "Nix-packaged JDK 25";
          inherit codec;
        };
      };
    };
  };
}
