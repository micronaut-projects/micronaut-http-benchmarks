{ config, lib, modulesPath, pkgs, ... }:
{
  imports = [
    (modulesPath + "/profiles/minimal.nix")
    (modulesPath + "/virtualisation/oci-image.nix")
    ./minimal-base.nix
  ];

  services.cloud-init = {
    enable = true;
    network.enable = true;
    settings.datasource_list = [ "Oracle" ];
  };

  nixpkgs.flake = {
    setFlakeRegistry = false;
    setNixPath = false;
  };

  documentation = {
    enable = false;
    doc.enable = false;
    info.enable = false;
    man.enable = false;
  };

  programs.command-not-found.enable = false;
  system.copySystemConfiguration = false;

  virtualisation.diskSize = 4096;

  image.baseName = "nixos";

  system.build.OCIImage = lib.mkForce (import (modulesPath + "/../lib/make-disk-image.nix") {
    inherit config lib pkgs;
    inherit (config.virtualisation) diskSize;
    name = "oci-image";
    baseName = config.image.baseName;
    configFile = pkgs.writeText "oci-config-user.nix" ''
      { modulesPath, ... }:
      {
        imports = [ "''${modulesPath}/virtualisation/oci-common.nix" ];
      }
    '';
    format = "qcow2-compressed";
    partitionTableType = "efi";
    copyChannel = false;
  });
}
