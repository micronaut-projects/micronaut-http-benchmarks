{ pkgs, ... }:
let
  vertx = pkgs.callPackage ./package.nix { };
in
{
  imports = [
    ../../nix/system/benchmark-bootstrap.nix
  ];

  networking.firewall.allowedTCPPorts = [ 8080 8443 ];

  systemd.services.vertx = {
    description = "Vert.x benchmark server";
    after = [ "network-online.target" ];
    wants = [ "network-online.target" ];

    serviceConfig = {
      Type = "notify";
      NotifyAccess = "all";
      ExecStart = "${vertx}/bin/vertx";
      DynamicUser = true;
      Restart = "no";
      StandardOutput = "journal";
      StandardError = "journal";
      TimeoutStartSec = 130;
    };
  };
}
