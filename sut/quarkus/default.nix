{ pkgs, ... }:
{
  benchmark = {
    jvm.enable = true;
    sut = {
      package = pkgs.callPackage ./package.nix { };
      executable = "quarkus";
      description = "Quarkus benchmark server";
      environment = [ "BENCHMARK_SYSTEMD_READINESS_ENABLED=true" ];
      metadata = {
        type = "quarkus-hotspot";
        parameters.runtime = "Nix-packaged JDK 25";
      };
    };
  };
}
