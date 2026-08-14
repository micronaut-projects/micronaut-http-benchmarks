{ pkgs, ... }:
let
  springBoot = pkgs.callPackage ./package.nix { };
in
{
  imports = [
    ../../nix/system/benchmark-bootstrap.nix
  ];

  networking.firewall.allowedTCPPorts = [ 8080 8443 ];

  systemd.services.spring-boot = {
    description = "Spring Boot benchmark server";
    after = [ "network-online.target" ];
    wants = [ "network-online.target" ];

    serviceConfig = {
      Type = "notify";
      NotifyAccess = "all";
      ExecStart = "${springBoot}/bin/spring-boot";
      Environment = "SPRING_SYSTEMD_NOTIFY_ENABLED=true";
      DynamicUser = true;
      Restart = "no";
      StandardOutput = "journal";
      StandardError = "journal";
      TimeoutStartSec = 130;
    };
  };
}
