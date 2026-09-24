{ pkgs, ... }:
let
  hyperfoil = import ./hyperfoil.nix { inherit pkgs; };

  seedAgentLib = pkgs.writeShellScript "hyperfoil-agent-seed-lib" ''
    set -eu
    ${pkgs.coreutils}/bin/rm -rf /tmp/hyperfoil/agentlib
    ${pkgs.coreutils}/bin/install -d -m 0700 /tmp/hyperfoil/agentlib
    ${pkgs.coreutils}/bin/cp -rL ${hyperfoil}/lib/. /tmp/hyperfoil/agentlib/
    ${pkgs.coreutils}/bin/chmod -R u+rwX /tmp/hyperfoil/agentlib
  '';
in
{
  imports = [
    ./base.nix
  ];

  benchmark.roleUnits = [
    "hyperfoil-agent-seed.service"
  ];

  benchmark.oci.instance = {
    shape = "VM.Standard.E5.Flex";
    ocpus = 8;
    memoryInGb = 32;
    platform = "x86_64-linux";
    diskPerformanceUnits = 80;
  };

  environment.systemPackages = [
    pkgs.jdk25_headless
    pkgs.coreutils
    pkgs.util-linux
    pkgs.procps
    pkgs.sudo
  ];

  systemd.services.hyperfoil-agent-seed = {
    description = "Seed Hyperfoil agent library";
    after = [ "local-fs.target" ];
    before = [ "sshd.service" ];
    serviceConfig = {
      Type = "oneshot";
      ExecStart = seedAgentLib;
    };
  };
}
