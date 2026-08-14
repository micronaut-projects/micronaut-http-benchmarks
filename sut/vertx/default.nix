{ pkgs, ... }:
{
  benchmark = {
    jvm.enable = true;
    sut = {
      package = pkgs.callPackage ./package.nix { };
      executable = "vertx";
      description = "Vert.x benchmark server";
      metadata = {
        type = "vertx-hotspot";
        parameters.runtime = "Nix-packaged JDK 25";
      };
    };
  };
}
