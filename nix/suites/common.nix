{ config, lib, ... }:
let
  inherit (lib) mkDefault mkOption optionalAttrs types;
  positive = types.addCheck types.int (value: value > 0);
  duration = types.addCheck types.str (value:
    builtins.match "^[0-9]+(ns|us|ms|s|m|h|d)$" value != null
  );
  nonEmpty = type: types.addCheck type (value: value != [ ] && value != { });
  cfg = config.benchmark.suite.protocols;
  defaultSla = { "0.50" = "100ms"; "0.95" = "200ms"; "0.99" = "1000ms"; };
  protocolControls = defaults: {
    enable = mkOption { type = types.bool; default = defaults.enable; };
    protocol = mkOption { type = types.enum [ "HTTP1" "HTTPS1" "HTTPS2" ]; default = defaults.protocol; };
    sharedConnections = mkOption { type = positive; default = defaults.sharedConnections; };
    pipeliningLimit = mkOption { type = positive; default = defaults.pipeliningLimit; };
    maxHttp2Streams = mkOption { type = positive; default = defaults.maxHttp2Streams; };
    compileOps = mkOption { type = positive; default = defaults.compileOps; };
    ops = mkOption { type = nonEmpty (types.listOf positive); default = defaults.ops; };
    sla = mkOption { type = nonEmpty (types.attrsOf duration); default = defaultSla; };
  };
in {
  options.benchmark.suite.protocols = {
    http1 = protocolControls {
      enable = false;
      protocol = "HTTP1";
      sharedConnections = 8000;
      pipeliningLimit = 1;
      maxHttp2Streams = 1;
      compileOps = 100;
      ops = [ 2000 16000 64000 96000 128000 160000 192000 256000 ];
    };
    https1 = protocolControls {
      enable = false;
      protocol = "HTTPS1";
      sharedConnections = 8000;
      pipeliningLimit = 1;
      maxHttp2Streams = 1;
      compileOps = 25;
      ops = [ 1000 4000 8000 16000 32000 64000 80000 ];
    };
    https2 = protocolControls {
      enable = false;
      protocol = "HTTPS2";
      sharedConnections = 265;
      pipeliningLimit = 1;
      # Hyperfoil's setting is a signed Java int. Keep stream concurrency out of the benchmark limit.
      maxHttp2Streams = 2147483647;
      compileOps = 25;
      ops = [ 1000 4000 8000 16000 32000 64000 80000 ];
    };
  };

  config.benchmark.suite = {
    statusRequest = mkDefault { name = "status"; uri = "/status"; };
    resolvedProtocols =
      optionalAttrs cfg.http1.enable { http1 = removeAttrs cfg.http1 [ "enable" ]; }
      // optionalAttrs cfg.https1.enable { https1 = removeAttrs cfg.https1 [ "enable" ]; }
      // optionalAttrs cfg.https2.enable { https2 = removeAttrs cfg.https2 [ "enable" ]; };
  };
}
