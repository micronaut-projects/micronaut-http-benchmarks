{
  imports = [ ./base.nix ];

  benchmark.oci.instance = {
    shape = "VM.Standard.E5.Flex";
    ocpus = 8;
    memoryInGb = 16;
    platform = "x86_64-linux";
    diskPerformanceUnits = 80;
  };
}
