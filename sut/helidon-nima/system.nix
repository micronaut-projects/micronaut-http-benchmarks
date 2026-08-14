{ pkgs, ... }:
let
  helidonNima = pkgs.callPackage ./package.nix { };
in
{
  imports = [
    ../../nix/system/benchmark-bootstrap.nix
  ];

  networking.firewall.allowedTCPPorts = [ 8080 8443 ];

  systemd.services.helidon-nima = {
    description = "Helidon Nima benchmark server";
    after = [ "network-online.target" ];
    wants = [ "network-online.target" ];

    serviceConfig = {
      Type = "notify";
      NotifyAccess = "all";
      ExecStart = "${helidonNima}/bin/helidon-nima";
      DynamicUser = true;
      Restart = "no";
      StandardOutput = "journal";
      StandardError = "journal";
      TimeoutStartSec = 130;
    };
  };
}
