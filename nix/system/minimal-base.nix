{ pkgs, lib, ... }:
{
  services.openssh = {
    enable = true;
    settings.PasswordAuthentication = false;
    settings.PermitRootLogin = "prohibit-password";
  };

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

  boot.loader = {
    systemd-boot.enable = true;
    efi.canTouchEfiVariables = false;
  };
}