{
  name = "pure-netty";
  system = ./system.nix;
  metadata = {
    type = "pure-netty-hotspot";
    name = "pure-netty-hotspot";
    parameters = {
      runtime = "Nix-packaged JDK 25";
    };
    serviceName = "pure-netty";
  };
}
