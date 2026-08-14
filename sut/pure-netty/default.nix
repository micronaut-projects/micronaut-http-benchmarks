{ pkgs, ... }:
{
  benchmark = {
    jvm.enable = true;
    sut = {
      package = pkgs.callPackage ./package.nix { };
      executable = "pure-netty";
      description = "Pure Netty benchmark server";
      metadata = {
        type = "pure-netty-hotspot";
        parameters.runtime = "Nix-packaged JDK 25";
      };
    };
  };
}
