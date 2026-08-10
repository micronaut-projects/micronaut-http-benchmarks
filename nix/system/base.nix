{ pkgs, lib, ... }:
{
  imports = [
    ./minimal-base.nix
  ];

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
}
