{ pkgs, lib, ... }:
{
  services.openssh = {
    enable = true;
    settings.PasswordAuthentication = false;
    settings.PermitRootLogin = "prohibit-password";
  };

  users.users.root.openssh.authorizedKeys.keys = import ../build/authorized_keys.nix;

  networking = {
    dhcpcd.enable = false;
    nftables.enable = true;
    useNetworkd = true;
    firewall = {
      enable = true;
      allowedTCPPorts = [ 22 ];
    };
  };

  systemd.network = {
    enable = true;
  };

  programs.vim = {
    enable = true;
    defaultEditor = true;
  };

  environment.enableAllTerminfo = true;

  environment.systemPackages = [
    pkgs.htop
    pkgs.tcpdump
    pkgs.tmux
    pkgs.mtr
    pkgs.magic-wormhole
  ];

  boot.loader = {
    systemd-boot.enable = true;
    efi.canTouchEfiVariables = false;
  };

  disko.devices = {
      disk.disk1 = {
        device = lib.mkDefault "/dev/sda";
        type = "disk";
        content = {
          type = "gpt";
          partitions = {
            boot = {
              name = "boot";
              size = "1M";
              type = "EF02";
            };
            esp = {
              name = "ESP";
              size = "500M";
              type = "EF00";
              content = {
                type = "filesystem";
                format = "vfat";
                mountpoint = "/boot";
              };
            };
            root = {
              name = "root";
              size = "100%";
              content = {
                type = "lvm_pv";
                vg = "pool";
              };
            };
          };
        };
      };
      lvm_vg = {
        pool = {
          type = "lvm_vg";
          lvs = {
            root = {
              size = "100%FREE";
              content = {
                type = "filesystem";
                format = "ext4";
                mountpoint = "/";
                mountOptions = [
                  "defaults"
                ];
              };
            };
          };
        };
      };
    };
}