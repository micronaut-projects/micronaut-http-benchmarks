{ ... }:
{
  imports = [ ./common.nix ./jvm-defaults.nix ];

  benchmark.suite = {
    attachments = [ "nginx" ];
    documents = [{
      name = "loop";
      method = "GET";
      uri = "/loop";
      responseBody = "Hello World";
      responseMatchingMode = "EQUAL";
    }];
    protocols.http1.enable = true;
    protocols.https1.enable = true;
    runs.micronaut = {
      imports = [ ../../sut/micronaut-framework ];
      micronaut-framework.codec = "micronaut-serialization";
    };
  };
}
