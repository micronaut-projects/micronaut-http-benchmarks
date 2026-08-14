{ config, pkgs, lib, ... }:
{
  imports = [
    ./minimal-base.nix
  ];

  options.benchmark = {
    roleUnits = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [ ];
    };

    oci.instance = {
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
  };

  config = {
    networking.firewall.extraInputRules = ''
      ip saddr 10.0.0.0/18 accept comment "benchmark private network"
    '';

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

    systemd.targets.benchmark-role-ready = {
      description = "Micronaut Framework benchmark role is ready";
      requires = config.benchmark.roleUnits;
      after = config.benchmark.roleUnits;
      wants = [ "sshd.service" ];
      before = [ "sshd.service" ];
      wantedBy = [ "multi-user.target" ];
    };

    systemd.services.sshd = {
      requires = [ "benchmark-role-ready.target" ];
      after = [ "benchmark-role-ready.target" ];
    };
  };
}
