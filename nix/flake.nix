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
    suts = [ (import ../sut/pure-netty) ];
    frameworkRunMetadata = map (sut: sut.metadata // {
      nixosConfiguration = sut.name;
    }) suts;

    mkHost = {
      system,
      definition
    }: lib.nixosSystem {
        inherit system;
        modules = [
          disko.nixosModules.disko
          definition
        ];
      };

    mkOciBootstrapImage = system: lib.nixosSystem {
      inherit system;
      modules = [ ./system/oci-bootstrap.nix ];
    };

    ociBootstrapImage = system: (mkOciBootstrapImage system).config.system.build.image;
  in {
    packages = lib.genAttrs supportedSystems (system:
      let
        pkgs = import nixpkgs { inherit system; };
        sutSystems = lib.listToAttrs (map (sut: lib.nameValuePair "${sut.name}-system" (mkHost {
          inherit system;
          definition = sut.system;
        }).config.system.build.toplevel) suts);
      in sutSystems // {
        nix-framework-runs = pkgs.writeTextFile {
          name = "nix-framework-runs.json";
          text = builtins.toJSON frameworkRunMetadata;
        };
        benchmark-bootstrap-system = (mkHost {
          inherit system;
          definition = ./system/benchmark-bootstrap.nix;
        }).config.system.build.toplevel;
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
