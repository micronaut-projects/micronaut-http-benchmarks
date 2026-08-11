{
  imports = [ ./base.nix ];

  benchmark.oci.instance = {
    shape = "PostgreSQL.VM.Standard.E5.Flex";
    ocpus = 4;
    memoryInGb = 32;
  };
}
