{ config, lib, pkgs, ... }:
let
  inherit (lib) mkIf mkOption types;
  cfg = config.benchmark;
in {
  options.benchmark = {
    run.name = mkOption {
      type = types.str;
      description = "The suite-local identity of this benchmark run.";
    };

    sut = {
      package = mkOption {
        type = types.nullOr types.package;
        default = null;
      };

      executable = mkOption {
        type = types.nullOr types.str;
        default = null;
      };

      description = mkOption {
        type = types.nullOr types.str;
        default = null;
      };

      environment = mkOption {
        type = types.listOf types.str;
        default = [ ];
      };

      metadata = {
        type = mkOption {
          type = types.nullOr types.str;
          default = null;
        };

        parameters = mkOption {
          type = types.attrsOf types.str;
          default = { };
        };
      };
    };

    jvm = {
      enable = mkOption {
        type = types.bool;
        default = false;
      };

      args = mkOption {
        type = types.listOf types.str;
        apply = lib.unique;
        default = [ ];
      };

      extraArgs = mkOption {
        type = types.listOf types.str;
        apply = lib.unique;
        default = [ ];
        description = "Arguments required by the selected JVM SUT in addition to user-configurable JVM arguments.";
      };
    };
  };

  config = lib.mkMerge [{
    assertions = [
      {
        assertion = cfg.sut.metadata.type != null;
        message = "A benchmark run must declare benchmark.sut.metadata.type.";
      }
    ] ++ lib.optionals cfg.jvm.enable [
      {
        assertion = cfg.sut.package != null;
        message = "A JVM benchmark run must declare benchmark.sut.package.";
      }
      {
        assertion = cfg.sut.executable != null;
        message = "A JVM benchmark run must declare benchmark.sut.executable.";
      }
      {
        assertion = cfg.sut.description != null;
        message = "A JVM benchmark run must declare benchmark.sut.description.";
      }
    ];
  } (mkIf cfg.jvm.enable {
      networking.firewall.allowedTCPPorts = [ 8080 8443 ];

      systemd.services.sut = {
        description = cfg.sut.description;
        after = [ "network-online.target" ];
        wants = [ "network-online.target" ];

        serviceConfig = {
          Type = "notify";
          NotifyAccess = "all";
          ExecStart = "${cfg.sut.package}/bin/${cfg.sut.executable}";
          Environment = cfg.sut.environment ++ [
            "JAVA_TOOL_OPTIONS=${builtins.concatStringsSep " " (cfg.jvm.args ++ cfg.jvm.extraArgs)}"
          ];
          DynamicUser = true;
          Restart = "no";
          StandardOutput = "journal";
          StandardError = "journal";
          TimeoutStartSec = 130;
        };
      };
  })];
}
