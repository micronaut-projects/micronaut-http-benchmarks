{ ... }:
{
  imports = [ ./common.nix ./jvm-defaults.nix ];

  benchmark.suite = {
    documents = [{
      name = "6-6";
      method = "POST";
      uri = "/search/find";
      requestBody = ''{"haystack":["ssxvnj","hpdqdx","vcrast","vybcwv","mgnykr","xvzxkg"],"needle":"bcw"}'';
      responseBody = ''{"listIndex":3,"stringIndex":2}'';
    }];
    protocols = {
      http1.ops = [ 2000 8000 16000 32000 64000 96000 128000 160000 192000 256000 ];
      https1.enable = true;
    };
    runs = {
      micronaut.imports = [ ../../sut/micronaut-framework ];
      pure-netty.imports = [ ../../sut/pure-netty ];
      helidon-nima.imports = [ ../../sut/helidon-nima ];
      spring-boot.imports = [ ../../sut/spring-boot ];
      vertx.imports = [ ../../sut/vertx ];
    };
  };
}
