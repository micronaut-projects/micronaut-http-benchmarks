{ ... }:
let
  micronaut = ../../sut/micronaut-framework;
in
{
  imports = [ ./common.nix ./jvm-defaults.nix ];

  # Each request makes an HTTP call to nginx. This is the blocking IO workload that virtual threads and
  # the loom carrier are meant for.
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
    runs = {
      # Reactive Micronaut HTTP client on the event loop.
      micronaut.imports = [ micronaut ];
      # Blocking Micronaut HTTP client on the blocking executor (virtual threads on the default scheduler).
      micronaut-blocking = {
        imports = [ micronaut ];
        micronaut-framework.executeOn = "blocking";
      };
      # Blocking Micronaut HTTP client on virtual threads carried by the event loop.
      micronaut-loom-carrier = {
        imports = [ micronaut ];
        micronaut-framework.threading = "loom-carrier";
        micronaut-framework.executeOn = "blocking";
      };
      # Blocking JDK HTTP client with one client per carrier event loop.
      micronaut-loom-carrier-jdk = {
        imports = [ micronaut ];
        micronaut-framework.threading = "loom-carrier";
        micronaut-framework.executeOn = "blocking";
        micronaut-framework.httpClient = "jdk";
      };
    };
  };
}
