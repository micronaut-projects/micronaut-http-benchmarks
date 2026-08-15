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
      http1.enable = true;
      https2.enable = true;
    };
    runs = {
      micronaut.imports = [ ../../sut/micronaut-framework ];
      pure-netty.imports = [ ../../sut/pure-netty ];
      helidon-nima.imports = [ ../../sut/helidon-nima ];
      #spring-boot.imports = [ ../../sut/spring-boot ];
      vertx.imports = [ ../../sut/vertx ];
    };
    asyncProfiler = {
      enable = true;
    };
  };
}
