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
      micronaut-loom-carrier = {
        imports = [ ../../sut/micronaut-framework ];
        micronaut-framework.threading = "loom-carrier";
      };
      #micronaut-native = { imports = [ ../../sut/micronaut-framework ]; benchmark.sut.runtime = "native"; };
      micronaut-native-virtual = {
        imports = [ ../../sut/micronaut-framework ];
        benchmark.sut.runtime = "native";
        micronaut-framework.threading = "virtual";
      };
      # Native Loom carrier variants are unsupported because they require private JDK APIs.
      #micronaut-pgo = { imports = [ ../../sut/micronaut-framework ]; benchmark.sut.runtime = "native-pgo"; };
      pyronaut.imports = [ ../../sut/pyronaut ];
      pyronaut-loom = {
        imports = [ ../../sut/pyronaut ];
        pyronaut.threading = "virtual";
      };
      pyronaut-loom-carrier = {
        imports = [ ../../sut/pyronaut ];
        pyronaut.threading = "loom-carrier";
      };
      pyronaut-native = { imports = [ ../../sut/pyronaut ]; benchmark.sut.runtime = "native"; };
      pyronaut-native-loom = {
        imports = [ ../../sut/pyronaut ];
        benchmark.sut.runtime = "native";
        pyronaut.threading = "virtual";
      };
      pure-netty.imports = [ ../../sut/pure-netty ];
      #helidon-nima.imports = [ ../../sut/helidon-nima ];
      #spring-boot.imports = [ ../../sut/spring-boot ];
      #quarkus.imports = [ ../../sut/quarkus ];
      #quarkus-native = { imports = [ ../../sut/quarkus ]; benchmark.sut.runtime = "native"; };
      #quarkus-pgo = { imports = [ ../../sut/quarkus ]; benchmark.sut.runtime = "native-pgo"; };
      flask-gunicorn.imports = [ ../../sut/flask-gunicorn ];
      #django-gunicorn.imports = [ ../../sut/django-gunicorn ];
      emmett-granian.imports = [ ../../sut/emmett-granian ];
      #vertx.imports = [ ../../sut/vertx ];
    };
    profiling = {
      enable = true;
    };
  };
}
