{
  name = "helidon-nima";
  system = ./system.nix;
  metadata = {
    type = "helidon-nima-hotspot";
    name = "helidon-nima-hotspot";
    parameters = {
      runtime = "Nix-packaged JDK 25";
    };
    serviceName = "helidon-nima";
  };
}
