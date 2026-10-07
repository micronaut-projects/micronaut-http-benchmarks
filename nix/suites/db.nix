{ ... }:
let
  micronaut = ../../sut/micronaut-framework;
in
{
  imports = [ ./common.nix ./jvm-defaults.nix ];

  # Each request runs a JDBC query against PostgreSQL on the blocking executor.
  benchmark.suite = {
    attachments = [ "postgresql" ];
    documents = [{
      name = "db";
      method = "GET";
      uri = "/db";
      responseBody = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
      responseMatchingMode = "REGEX";
    }];
    protocols.http1.enable = true;
    runs = {
      # Virtual threads on the default scheduler.
      micronaut.imports = [ micronaut ];
      # Virtual threads carried by the event loop.
      micronaut-loom-carrier = {
        imports = [ micronaut ];
        micronaut-framework.threading = "loom-carrier";
      };
    };
  };
}
