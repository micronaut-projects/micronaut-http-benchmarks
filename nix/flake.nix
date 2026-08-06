{
  description = "Micronaut benchmark infra";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";
    disko.url = "github:nix-community/disko";
    disko.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { nixpkgs, disko, ... }:
  let
    lib = nixpkgs.lib;

    mkHost = {
      definition
    }: lib.nixosSystem {
      modules = [
        disko.nixosModules.disko
        ./system/base.nix
        definition
        { hardware.facter.reportPath = ./build/facter.json; }
      ];
    };

    mkOciBootstrapImage = system: lib.nixosSystem {
      inherit system;
      modules = [ ./system/oci-bootstrap.nix ];
    };

    ociBootstrapImage = system: (mkOciBootstrapImage system).config.system.build.image;
  in {
    packages = lib.genAttrs [ "x86_64-linux" "aarch64-linux" ] (system: {
      oci-bootstrap-image = ociBootstrapImage system;
    });

    nixosConfigurations = {
      relay-server = mkHost { definition = ./system/relay-server.nix; };
    };
  };
}
