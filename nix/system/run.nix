{ config, lib, pkgs, ... }:
let
  inherit (lib) mkIf mkOption types;
  cfg = config.benchmark;
  asyncProfilerArgs = cfg.asyncProfiler.args;
  jvmArgs = cfg.jvm.args ++ cfg.jvm.extraArgs ++ lib.optional cfg.asyncProfiler.enable "-agentpath:${pkgs.async-profiler}/lib/libasyncProfiler.so=${asyncProfilerArgs},file=/var/lib/sut/profile.jfr";
in {
  options.benchmark = {
    run.name = mkOption {
      type = types.str;
      description = "The suite-local identity of this benchmark run.";
    };

    asyncProfiler = mkOption {
      type = types.submodule {
        options = {
          enable = mkOption {
            type = types.bool;
            default = false;
            description = "Enable the Nix-provided async-profiler JVM agent for this run.";
          };

          args = mkOption {
            type = types.str;
            default = "start,event=cpu,cstack=vm,jfrsync=default";
          };
        };
      };
      default = { };
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
        environment.JAVA_TOOL_OPTIONS = builtins.concatStringsSep " " jvmArgs;

        serviceConfig = {
          Type = "notify";
          NotifyAccess = "all";
          ExecStart = "${cfg.sut.package}/bin/${cfg.sut.executable}";
          Environment = cfg.sut.environment;
          DynamicUser = true;
          StateDirectory = lib.optional cfg.asyncProfiler.enable "sut";
          StateDirectoryMode = "0750";
          ExecStartPre = lib.optional cfg.asyncProfiler.enable "${pkgs.coreutils}/bin/rm -f /var/lib/sut/profile.jfr";
          Restart = "no";
          StandardOutput = "journal";
          StandardError = "journal";
          TimeoutStartSec = 130;
        };
        path = lib.optional cfg.asyncProfiler.enable pkgs.async-profiler;
      };
  }) (mkIf cfg.asyncProfiler.enable {
    boot.kernel.sysctl = {
      "kernel.perf_event_paranoid" = 1;
      "kernel.kptr_restrict" = 0;
    };
  })];
}
