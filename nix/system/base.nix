{ pkgs, lib, ... }:
{
  imports = [
    ./minimal-base.nix
  ];

  options.benchmark.oci.instance = {
    shape = lib.mkOption {
      type = lib.types.str;
    };

    ocpus = lib.mkOption {
      type = lib.types.number;
    };

    memoryInGb = lib.mkOption {
      type = lib.types.number;
    };

    platform = lib.mkOption {
      type = lib.types.nullOr (lib.types.enum [
        "x86_64-linux"
        "aarch64-linux"
      ]);
      default = null;
    };

    diskPerformanceUnits = lib.mkOption {
      type = lib.types.nullOr lib.types.int;
      default = null;
    };
  };

  config = {
    #users.users.root.openssh.authorizedKeys.keys = import ../build/authorized_keys.nix;

    programs.vim = {
      enable = true;
      defaultEditor = true;
    };

    environment.systemPackages = [
      pkgs.htop
      pkgs.tcpdump
      pkgs.tmux
      pkgs.mtr
      pkgs.magic-wormhole
    ];
  };
}
