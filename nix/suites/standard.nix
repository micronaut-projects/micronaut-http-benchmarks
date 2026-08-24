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
      requestBody = ''{"haystack":["ssxvnj","hpdqdx","vcrast","vybcwv","mgnykr","xvzxkg"],"needle":"bcw"}'';
      responseBody = ''{"listIndex":3,"stringIndex":2}'';
    }];
    protocols = {
      http1.enable = true;
      #https2.enable = true;
    };
    runs = {
      micronaut.imports = [ ../../sut/micronaut-framework ];
      micronaut-native = { imports = [ ../../sut/micronaut-framework ]; benchmark.sut.runtime = "native"; };
      micronaut-pgo = { imports = [ ../../sut/micronaut-framework ]; benchmark.sut.runtime = "native-pgo"; };
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
    asyncProfiler = {
      enable = true;
    };
  };
}
