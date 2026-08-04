{
  description = "Micronaut benchmark infra";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";
    disko.url = "github:nix-community/disko";
    disko.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { nixpkgs, disko, ... }:
  let
    mkHost = {
      definition
    }: nixpkgs.lib.nixosSystem {
      modules = [
        disko.nixosModules.disko
        ./system/base.nix
        definition
        { hardware.facter.reportPath = ./build/facter.json; }
      ];
    };
  in {
    nixosConfigurations = {
      relay-server = mkHost { definition = ./system/relay-server.nix; };
    };
  };
}
