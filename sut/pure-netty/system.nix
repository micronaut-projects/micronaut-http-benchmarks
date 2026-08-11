{ pkgs, ... }:
let
  pureNetty = pkgs.callPackage ./package.nix { };
in
{
  imports = [
    ../../nix/system/benchmark-bootstrap.nix
  ];

  networking.firewall.allowedTCPPorts = [ 8080 8443 ];

  systemd.services.pure-netty = {
    description = "Pure Netty benchmark server";
    after = [ "network-online.target" ];
    wants = [ "network-online.target" ];

    serviceConfig = {
      Type = "notify";
      NotifyAccess = "all";
      ExecStart = "${pureNetty}/bin/pure-netty";
      DynamicUser = true;
      Restart = "no";
      StandardOutput = "journal";
      StandardError = "journal";
      TimeoutStartSec = 130;
    };
  };
}
