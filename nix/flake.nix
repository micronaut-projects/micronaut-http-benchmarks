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
    supportedSystems = [ "x86_64-linux" "aarch64-linux" ];

    mkHost = {
      system,
      definition
    }: lib.nixosSystem {
      inherit system;
      modules = [
        disko.nixosModules.disko
        ./system/base.nix
        definition
      ];
    };

    mkOciBootstrapImage = system: lib.nixosSystem {
      inherit system;
      modules = [ ./system/oci-bootstrap.nix ];
    };

    ociBootstrapImage = system: (mkOciBootstrapImage system).config.system.build.image;
  in {
    packages = lib.genAttrs supportedSystems (system: {
      oci-bootstrap-image = ociBootstrapImage system;
      hyperfoil-agent-system = (mkHost {
        inherit system;
        definition = ./system/hyperfoil-agent.nix;
      }).config.system.build.toplevel;
      hyperfoil-controller-system = (mkHost {
        inherit system;
        definition = ./system/hyperfoil-controller.nix;
      }).config.system.build.toplevel;
      relay-server-system = (mkHost {
        inherit system;
        definition = ./system/relay-server.nix;
      }).config.system.build.toplevel;
    });
  };
}
