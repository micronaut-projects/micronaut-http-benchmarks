{ ... }:
{
  imports = [
    ./base.nix
  ];

  benchmark.oci.instance = {
    shape = "VM.Standard.E4.Flex";
    ocpus = 3;
    memoryInGb = 24;
    platform = "x86_64-linux";
    diskPerformanceUnits = 80;
  };
}
