{ pkgs, ... }:
{
  benchmark = {
    jvm.enable = true;
    sut = {
      package = pkgs.callPackage ./package.nix { };
      executable = "helidon-nima";
      description = "Helidon Nima benchmark server";
      metadata = {
        type = "helidon-nima-hotspot";
        parameters.runtime = "Nix-packaged JDK 25";
      };
    };
  };
}
