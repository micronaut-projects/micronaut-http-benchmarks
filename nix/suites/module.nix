{ lib, ... }:
let
  inherit (lib) mkOption types;
  positive = types.addCheck types.int (value: value > 0);
  nonEmpty = type: types.addCheck type (value: value != [ ] && value != { });
  duration = types.addCheck types.str (value: builtins.match "[0-9]+(ns|us|ms|s|m|h|d)" value != null);
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
  };

}
