{ config, lib, ... }:
let
  inherit (lib) mkIf mkOption types;
  benchmarkTypes = import ./types.nix { inherit lib; };
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
      type = types.listOf benchmarkTypes.request;
      default = [ ];
    };

    statusRequest = mkOption {
      type = benchmarkTypes.request;
    };

    resolvedProtocols = mkOption {
      type = types.attrsOf benchmarkTypes.protocolRecord;
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
      enable = lib.mkDefault true;
      args = config.benchmark.suite.asyncProfiler.args;
    };
  }];
}
