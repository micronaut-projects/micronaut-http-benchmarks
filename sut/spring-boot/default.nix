{
  name = "spring-boot";
  system = ./system.nix;
  metadata = {
    type = "spring-boot-hotspot";
    name = "spring-boot-hotspot";
    parameters = {
      runtime = "Nix-packaged JDK 25";
    };
    serviceName = "spring-boot";
  };
}
