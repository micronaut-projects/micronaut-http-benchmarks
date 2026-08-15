{ config, lib, ... }:
let
  inherit (lib) mkIf mkOption types;
  positive = types.addCheck types.int (value: value > 0);
  nonEmpty = type: types.addCheck type (value: value != [ ] && value != { });
  duration = types.addCheck types.str (value:
    builtins.match "^P([0-9]+D)?(T([0-9]+H)?([0-9]+M)?([0-9]+(\\.[0-9]+)?S)?)?$" value != null
    && builtins.match ".*[0-9].*" value != null
  );
  protocolRecord = types.submodule {
    options = {
      protocol = mkOption { type = types.enum [ "HTTP1" "HTTPS1" "HTTPS2" ]; };
      sharedConnections = mkOption { type = positive; };
      pipeliningLimit = mkOption { type = positive; default = 1; };
      maxHttp2Streams = mkOption { type = positive; default = 1; };
      compileOps = mkOption { type = positive; };
      ops = mkOption { type = nonEmpty (types.listOf positive); };
      sla = mkOption { type = nonEmpty (types.attrsOf duration); };
    };
  };
  request = types.submodule {
    options = {
      name = mkOption { type = types.str; };
      method = mkOption { type = types.nullOr types.str; default = null; };
      uri = mkOption { type = types.str; };
      host = mkOption { type = types.nullOr types.str; default = null; };
      requestType = mkOption { type = types.nullOr types.str; default = null; };
      requestHeaders = mkOption {
        type = types.attrsOf types.str;
        default = { };
      };
      requestBody = mkOption { type = types.nullOr types.str; default = null; };
      responseBody = mkOption { type = types.nullOr types.str; default = null; };
      responseMatchingMode = mkOption {
        type = types.nullOr (types.enum [ "EQUAL" "JSON" "REGEX" ]);
        default = null;
      };
    };
  };
  asyncProfiler = types.submodule {
    options = {
      enable = mkOption {
        type = types.bool;
        default = false;
      };

      args = mkOption {
        type = types.str;
        default = "start,event=cpu,cstack=vm,jfrsync=default";
      };
    };
  };
in {
  options.benchmark.suite = {
    documents = mkOption {
      type = types.listOf request;
      default = [ ];
    };

    statusRequest = mkOption {
      type = request;
    };

    resolvedProtocols = mkOption {
      type = types.attrsOf protocolRecord;
      readOnly = true;
    };

    runs = mkOption {
      type = types.attrsOf types.deferredModule;
      default = { };
      description = "Suite-local run modules, evaluated independently as NixOS systems.";
    };

    runModules = mkOption {
      type = types.listOf types.deferredModule;
      default = [ ];
      description = "Reusable aspects composed into every run in this suite.";
    };

    asyncProfiler = mkOption {
      type = asyncProfiler;
      default = { };
      description = "Configure async-profiler for JVM SUT runs in this suite.";
    };
  };

  config.benchmark.suite.runModules = mkIf config.benchmark.suite.asyncProfiler.enable [{
    benchmark.asyncProfiler = {
      enable = true;
      args = config.benchmark.suite.asyncProfiler.args;
    };
  }];
}
