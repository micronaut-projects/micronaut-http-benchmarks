{
  name = "vertx";
  system = ./system.nix;
  metadata = {
    type = "vertx-hotspot";
    name = "vertx-hotspot";
    parameters = {
      runtime = "Nix-packaged JDK 25";
    };
    serviceName = "vertx";
  };
}
