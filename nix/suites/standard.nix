{ ... }:
{
  imports = [ ./common.nix ./jvm-defaults.nix ];

  benchmark.suite = {
    documents = [{
      name = "6-6";
      method = "POST";
      uri = "/search/find";
      requestHeaders = {
        Content-Type = "application/json";
      };
      host = "example.com";
      requestBody = ''{"haystack":["ssxvnj","hpdqdx","vcrast","vybcwv","mgnykr","xvzxkg"],"needle":"bcw"}'';
      responseBody = ''{"listIndex":3,"stringIndex":2}'';
    }];
    protocols = {
      #http1.enable = true;
      https2.enable = true;
    };
    runs = {
      micronaut.imports = [ ../../sut/micronaut-framework ];
      micronaut-loom = {
        imports = [ ../../sut/micronaut-framework ];
        micronaut-framework.threading = "virtual";
      };
      #micronaut-loom-carrier = {
      #  imports = [ ../../sut/micronaut-framework ];
      #  micronaut-framework.threading = "loom-carrier";
      #};
      #micronaut-native = { imports = [ ../../sut/micronaut-framework ]; benchmark.sut.runtime = "native"; };
      #micronaut-native-virtual = {
      #  imports = [ ../../sut/micronaut-framework ];
      #  benchmark.sut.runtime = "native";
      #  micronaut-framework.threading = "virtual";
      #};
      # Native Loom carrier variants are unsupported because they require private JDK APIs.
      #micronaut-pgo = { imports = [ ../../sut/micronaut-framework ]; benchmark.sut.runtime = "native-pgo"; };
      #pyronaut-async.imports = [ ../../sut/pyronaut ];
      pyronaut-io = {
        imports = [ ../../sut/pyronaut ];
        pyronaut.threading = "io";
      };
      #pyronaut-native-async = { imports = [ ../../sut/pyronaut ]; benchmark.sut.runtime = "native"; };
      #pyronaut-native-io = {
      #  imports = [ ../../sut/pyronaut ];
      #  benchmark.sut.runtime = "native";
      #  pyronaut.threading = "io";
      #};
      pure-netty.imports = [ ../../sut/pure-netty ];
      #helidon-nima.imports = [ ../../sut/helidon-nima ];
      #spring-boot.imports = [ ../../sut/spring-boot ];
      #quarkus.imports = [ ../../sut/quarkus ];
      #quarkus-native = { imports = [ ../../sut/quarkus ]; benchmark.sut.runtime = "native"; };
      #quarkus-pgo = { imports = [ ../../sut/quarkus ]; benchmark.sut.runtime = "native-pgo"; };
      flask-gunicorn = { imports = [ ../../sut/flask ]; benchmark.python.server = "gunicorn"; };
      #flask-granian = { imports = [ ../../sut/flask ]; benchmark.python.server = "granian"; };
      #fastapi-gunicorn = { imports = [ ../../sut/fastapi ]; benchmark.python.server = "gunicorn"; };
      fastapi-granian = { imports = [ ../../sut/fastapi ]; benchmark.python.server = "granian"; };
      #django-gunicorn = { imports = [ ../../sut/django ]; benchmark.python.server = "gunicorn"; };
      #django-granian = { imports = [ ../../sut/django ]; benchmark.python.server = "granian"; };
      #emmett-granian = { imports = [ ../../sut/emmett ]; benchmark.python.server = "granian"; };
      #emmett-gunicorn = { imports = [ ../../sut/emmett ]; benchmark.python.server = "gunicorn"; };
      #vertx.imports = [ ../../sut/vertx ];
    };
    profiling = {
      enable = false;
    };
  };
}
