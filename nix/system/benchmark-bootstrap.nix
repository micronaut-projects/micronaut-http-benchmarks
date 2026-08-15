{ pkgs, ... }:
{
  imports = [
    ./base.nix
  ];

  systemd.services.benchmark-prefetch = {
    description = "Prefetch benchmark Nix closures";
    wantedBy = [ "benchmark-role-ready.target" ];
    after = [ "network-online.target" ];
    wants = [ "network-online.target" ];
    serviceConfig = {
      Type = "simple";
      StandardOutput = "journal+console";
      StandardError = "journal+console";
    };
    script = ''
      script=/etc/credstore/benchmark-prefetch/script
      running=/run/benchmark-prefetch-script
      if [ ! -f "$script" ]; then
        echo "nix prefetch not configured"
        exit 0
      fi
      mv "$script" "$running"
      exec ${pkgs.bash}/bin/bash "$running"
    '';
  };

  benchmark.oci.instance = {
    shape = "VM.Standard.E4.Flex";
    ocpus = 3;
    memoryInGb = 24;
    platform = "x86_64-linux";
    diskPerformanceUnits = 80;
  };
}
