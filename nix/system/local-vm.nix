{ lib, pkgs, ... }:
{
  environment.systemPackages = [ pkgs.curl ];

  virtualisation = {
    memorySize = 8192;
    cores = 4;
  };

  systemd.services.sut.serviceConfig = {
    StandardOutput = lib.mkForce "journal+console";
    StandardError = lib.mkForce "journal+console";
  };
}
