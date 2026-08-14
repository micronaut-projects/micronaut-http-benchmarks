{ ... }:
{
  imports = [ ./common.nix ./jvm-defaults.nix ];

  benchmark.suite = {
    documents = [{
      name = "db";
      method = "GET";
      uri = "/db";
      responseBody = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
      responseMatchingMode = "REGEX";
    }];
    runs.micronaut = {
      imports = [ ../../sut/micronaut-framework ];
      micronaut-framework.codec = "micronaut-serialization";
    };
  };
}
