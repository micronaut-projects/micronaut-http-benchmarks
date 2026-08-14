{
  name = "micronaut-framework";
  system = ./system.nix;
  metadata = {
    type = "micronaut-framework-hotspot";
    name = "micronaut-framework-hotspot";
    parameters = {
      runtime = "Nix-packaged JDK 25";
    };
    serviceName = "micronaut-framework";
  };
}
