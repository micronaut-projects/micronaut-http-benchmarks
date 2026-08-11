{ pkgs, ... }:
{
  imports = [
    ./base.nix
  ];

  environment.systemPackages = [
    pkgs.jdk25_headless
    pkgs.coreutils
    pkgs.util-linux
    pkgs.procps
    pkgs.sudo
  ];
}
