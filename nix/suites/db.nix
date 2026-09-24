{ ... }:
{
  imports = [ ./common.nix ./jvm-defaults.nix ];

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
    runs.micronaut.imports = [ ../../sut/micronaut-framework ];
  };
}
