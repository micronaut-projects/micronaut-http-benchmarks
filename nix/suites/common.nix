{ config, lib, ... }:
let
  inherit (lib) mkDefault mkOption optionalAttrs types;
  positive = types.addCheck types.int (value: value > 0);
  duration = types.addCheck types.str (value: builtins.match "[0-9]+(ns|us|ms|s|m|h|d)" value != null);
  nonEmpty = type: types.addCheck type (value: value != [ ] && value != { });
  cfg = config.benchmark.suite.protocols;
  http1Ops = [ 2000 8000 16000 32000 48000 64000 96000 128000 160000 192000 256000 ];
  https1Ops = [ 1000 4000 8000 16000 24000 32000 48000 64000 80000 ];
  protocolControls = defaults: {
    enable = mkOption { type = types.bool; default = defaults.enable; };
    protocol = mkOption { type = types.enum [ "HTTP1" "HTTPS1" "HTTPS2" ]; default = defaults.protocol; };
    sharedConnections = mkOption { type = positive; default = defaults.sharedConnections; };
    pipeliningLimit = mkOption { type = positive; default = defaults.pipeliningLimit; };
    maxHttp2Streams = mkOption { type = positive; default = defaults.maxHttp2Streams; };
    compileOps = mkOption { type = positive; default = defaults.compileOps; };
    ops = mkOption { type = nonEmpty (types.listOf positive); default = defaults.ops; };
    sla = mkOption { type = nonEmpty (types.attrsOf duration); default = defaults.sla; };
  };
in {
  options.benchmark.suite.protocols = {
    http1 = protocolControls {
      enable = true;
      protocol = "HTTP1";
      sharedConnections = 16000;
      pipeliningLimit = 1;
      maxHttp2Streams = 1;
      compileOps = 1000;
      ops = http1Ops;
      sla = { "0.99" = "200ms"; };
    };
    https1 = protocolControls {
      enable = false;
      protocol = "HTTPS1";
      sharedConnections = 20000;
      pipeliningLimit = 1;
      maxHttp2Streams = 1;
      compileOps = 1000;
      ops = https1Ops;
      sla = { "0.99" = "200ms"; };
    };
    https2 = protocolControls {
      enable = false;
      protocol = "HTTPS2";
      sharedConnections = 265;
      pipeliningLimit = 1;
      maxHttp2Streams = 100;
      compileOps = 25;
      ops = https1Ops;
      sla = { "0.99" = "200ms"; };
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
