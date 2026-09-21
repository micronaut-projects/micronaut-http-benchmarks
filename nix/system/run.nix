{ config, lib, pkgs, ... }:
let
  inherit (lib) mkIf mkOption types;
  cfg = config.benchmark;
  runtimeProjection =
    let
      runtime = cfg.sut.runtime;
      isJvm = runtime == "hotspot";
      isNative = runtime == "native" || runtime == "native-pgo-instrument" || runtime == "native-pgo";
      isPgo = runtime == "native-pgo-instrument" || runtime == "native-pgo";
      toolchain = if isJvm then cfg.toolchains.java else cfg.toolchains.graalvm;
    in if runtime == "python" then {
      buildPackage = pkgs.python3;
      inherit isJvm isNative isPgo;
      label = "python";
      displayName = "Nix-packaged Python ${pkgs.python3.version}";
      keepDebugSymbols = false;
      nativeImageArgs = [ ];
    } else {
      buildPackage = toolchain.package;
      inherit isJvm isNative isPgo;
      label = if isPgo then "${toolchain.metadataLabel}-pgo" else toolchain.metadataLabel;
      displayName = "${toolchain.displayName}${lib.optionalString (!isJvm) " native image"}${lib.optionalString isPgo " PGO"}";
      keepDebugSymbols = isNative && cfg.profiling.enable;
      nativeImageArgs = lib.optionals (isNative && cfg.profiling.enable) [
        "-g"
        "-H:+PreserveFramePointer"
        "-H:-DeleteLocalSymbols"
      ];
    };
  profilingTool = if runtimeProjection.isJvm then "async-profiler" else if runtimeProjection.isNative then "perf" else "py-spy";
  profilingArtifact = if runtimeProjection.isJvm then "profile.jfr" else if runtimeProjection.isNative then "profile.data" else "profile.txt";
  profilingPath = "/var/lib/sut/${profilingArtifact}";
  profilingPackage = if runtimeProjection.isJvm then pkgs.async-profiler else if runtimeProjection.isNative then pkgs.linuxPackages.perf else pkgs.py-spy;
  jvmArgs = cfg.jvm.args ++ cfg.jvm.extraArgs ++ lib.optional cfg.profiling.enable "-agentpath:${pkgs.async-profiler}/lib/libasyncProfiler.so=${cfg.profiling.args},file=${profilingPath}";
  sutCommand = "${cfg.sut.package}/bin/${cfg.sut.executable}";
  profilingLauncher = pkgs.writeShellScript "benchmark-profile-${cfg.run.name}" (if runtimeProjection.isNative then ''
    exec ${pkgs.linuxPackages.perf}/bin/perf record -k 1 -F 99 -e cpu-clock --call-graph fp -o ${lib.escapeShellArg profilingPath} -- ${lib.escapeShellArg sutCommand}
  '' else ''
    exec ${pkgs.py-spy}/bin/py-spy record --rate 1 --format raw --subprocesses -o ${lib.escapeShellArg profilingPath} -- ${lib.escapeShellArg sutCommand}
  '');
  nativeProfileCleanup = pkgs.writeShellScript "benchmark-clean-native-profile-${cfg.run.name}" ''
    set -eu
    rm -rf \
      /var/lib/sut/profile.data \
      /var/lib/sut/profile.jit.data \
      /var/lib/sut/profile-symbols \
      /var/lib/sut/jitdump
    install -d -m 0755 /var/lib/sut/jitdump
  '';
  nativeProfileFinalizer = pkgs.writeShellScript "benchmark-finalize-native-profile-${cfg.run.name}" ''
    set -eu
    raw_profile=/var/lib/sut/profile.data
    injected_profile=/var/lib/sut/profile.jit.data
    symbol_directory=/var/lib/sut/profile-symbols
    package=${lib.escapeShellArg (toString cfg.sut.package)}

    rm -f "$injected_profile"
    rm -rf "$symbol_directory"
    install -d -m 0755 "$symbol_directory"
    install -D -m 0644 /proc/kallsyms "$symbol_directory/proc/kallsyms"
    test -s "$symbol_directory/proc/kallsyms"
    perf inject -j -i "$raw_profile" -o "$injected_profile"

    elf_manifest=$(mktemp)
    jit_manifest=$(mktemp)
    trap 'rm -f "$elf_manifest" "$jit_manifest"' EXIT

    elf_staged=false
    find -L "$package" -type f -perm /111 -print0 > "$elf_manifest"
    while IFS= read -r -d "" source; do
      if readelf -h "$source" > /dev/null 2>&1; then
        elf_staged=true
        destination="$symbol_directory$source"
        install -d -m 0755 "$(dirname "$destination")"
        cp --preserve=mode,timestamps "$source" "$destination"
      fi
    done < "$elf_manifest"
    test "$elf_staged" = true

    find /var/lib/sut/jitdump -type f -print0 > "$jit_manifest"
    while IFS= read -r -d "" source; do
      destination="$symbol_directory$source"
      install -d -m 0755 "$(dirname "$destination")"
      cp --preserve=mode,timestamps "$source" "$destination"
    done < "$jit_manifest"
  '';
in {
  options.benchmark = {
    run.name = mkOption {
      type = types.str;
      description = "The suite-local identity of this benchmark run.";
    };

    profiling = mkOption {
      type = types.submodule {
        options = {
          enable = mkOption {
            type = types.bool;
            default = false;
            description = "Enable the runtime-specific low-overhead profiler for this run.";
          };

          args = mkOption {
            type = types.str;
            default = "start,event=cpu,cstack=vm,jfrsync=default";
          };
        };
      };
      default = { };
    };

    toolchains = {
      java = {
        package = mkOption {
          type = types.package;
          default = pkgs.jdk25_headless;
          description = "Java package used to build and run HotSpot benchmark SUTs.";
        };
        displayName = mkOption {
          type = types.str;
          default = "Nix-packaged JDK 25";
          description = "Display name for the Java runtime in benchmark metadata.";
        };
        metadataLabel = mkOption {
          type = types.str;
          default = "hotspot";
          description = "Metadata type label for Java benchmark runs.";
        };
      };
      graalvm = {
        package = mkOption {
          type = types.package;
          default = pkgs.graalvmPackages.graalvm-oracle_25.overrideAttrs (previous: {
            postFixup = (previous.postFixup or "") + ''
              sed -i 's| -H:CLibraryPath=[^ ]*-glibc-[^ ]*-static/lib||' "$out/bin/.native-image-wrapped_"
            '';
          });
          description = "Patched Oracle GraalVM package used to build native benchmark SUTs.";
        };
        displayName = mkOption {
          type = types.str;
          default = "GraalVM Oracle 25";
          description = "Display name for the GraalVM runtime in benchmark metadata.";
        };
        metadataLabel = mkOption {
          type = types.str;
          default = "native";
          description = "Metadata type label for GraalVM native-image benchmark runs.";
        };
      };
    };

    sut = {
      runtime = mkOption {
        type = types.enum [ "hotspot" "native" "native-pgo-instrument" "native-pgo" "python" ];
        default = "hotspot";
      };
      runtimeInfo = mkOption {
        type = types.submodule {
          options = {
            buildPackage = mkOption { type = types.package; };
            isJvm = mkOption { type = types.bool; };
            isNative = mkOption { type = types.bool; };
            isPgo = mkOption { type = types.bool; };
            keepDebugSymbols = mkOption { type = types.bool; };
            label = mkOption { type = types.str; };
            displayName = mkOption { type = types.str; };
            nativeImageArgs = mkOption { type = types.listOf types.str; };
          };
        };
        default = runtimeProjection;
        readOnly = true;
        description = "Derived build package, runtime flags, and metadata for the selected SUT runtime.";
      };
      package = mkOption {
        type = types.nullOr types.package;
        default = null;
      };

      pgoProfile = mkOption {
        type = types.nullOr types.package;
        default = null;
        internal = true;
        description = "Build-time training output containing default.iprof.";
      };

      executable = mkOption {
        type = types.nullOr types.str;
        default = null;
      };

      description = mkOption {
        type = types.nullOr types.str;
        default = null;
      };

      tlsHttp2 = mkOption {
        type = types.bool;
        default = false;
        description = "Whether the SUT serves HTTP/2 over TLS on port 8443.";
      };

      environment = mkOption {
        type = types.listOf types.str;
        default = [ ];
      };

      metadata = {
        typePrefix = mkOption {
          type = types.nullOr types.str;
          default = null;
          description = "Required SUT-specific prefix for the composed benchmark metadata type.";
        };

        typeSuffix = mkOption {
          type = types.nullOr types.str;
          default = null;
          description = "Optional SUT-specific suffix for the composed benchmark metadata type.";
        };

        type = mkOption {
          type = types.nullOr types.str;
          default = null;
        };

        parameters = mkOption {
          type = types.attrsOf types.str;
          default = { };
        };

        profiling = mkOption {
          type = types.nullOr (types.submodule {
            options = {
              tool = mkOption { type = types.enum [ "async-profiler" "perf" "py-spy" ]; };
              artifact = mkOption { type = types.str; };
              injectedArtifact = mkOption {
                type = types.nullOr types.str;
                default = null;
              };
              symbolDirectory = mkOption {
                type = types.nullOr types.str;
                default = null;
              };
            };
          });
          default = null;
        };

        enabled = mkOption {
          type = types.bool;
          default = true;
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
        assertion = cfg.sut.metadata.typePrefix != null;
        message = "A benchmark run must declare benchmark.sut.metadata.typePrefix.";
      }
      {
        assertion = cfg.sut.metadata.type != null;
        message = "A benchmark run must declare benchmark.sut.metadata.type.";
      }
    ] ++ [
      {
        assertion = cfg.sut.package != null;
        message = "A benchmark service run must declare benchmark.sut.package.";
      }
      {
        assertion = cfg.sut.executable != null;
        message = "A benchmark service run must declare benchmark.sut.executable.";
      }
      {
        assertion = cfg.sut.description != null;
        message = "A benchmark service run must declare benchmark.sut.description.";
      }
    ];
    users.groups.sut = { };
    benchmark.sut.metadata = {
      type = builtins.concatStringsSep "-" ([ cfg.sut.metadata.typePrefix cfg.sut.runtimeInfo.label ] ++ lib.optional (cfg.sut.metadata.typeSuffix != null) cfg.sut.metadata.typeSuffix);
      parameters.runtime = cfg.sut.runtimeInfo.displayName;
      profiling = if cfg.profiling.enable then {
        tool = profilingTool;
        artifact = profilingArtifact;
        injectedArtifact = if cfg.sut.runtimeInfo.isNative then "profile.jit.data" else null;
        symbolDirectory = if cfg.sut.runtimeInfo.isNative then "profile-symbols" else null;
      } else null;
    };
    users.users.sut = {
      isSystemUser = true;
      group = "sut";
    };
    system.activationScripts.sutStateDirectory = ''
      if [ -L /var/lib/sut ]; then
        rm /var/lib/sut
      fi
      rm -rf /var/lib/private/sut
      install -d -o sut -g sut -m 0755 /var/lib/sut
    '';
    networking.firewall.allowedTCPPorts = [ 8080 8443 ];
    systemd.services.sut = {
      description = cfg.sut.description;
      after = [ "network-online.target" ];
      wants = [ "network-online.target" ];
      environment = lib.optionalAttrs cfg.jvm.enable {
        JAVA_TOOL_OPTIONS = builtins.concatStringsSep " " (jvmArgs ++ [ "-Dbenchmark.tls.directory=/etc/benchmark-tls" ]);
      };
      path = lib.optional cfg.profiling.enable profilingPackage
        ++ lib.optionals (cfg.profiling.enable && cfg.sut.runtimeInfo.isNative) [ pkgs.binutils pkgs.coreutils pkgs.findutils ];
      serviceConfig = {
        Type = "notify";
        NotifyAccess = "all";
        ExecStart = if cfg.profiling.enable && !cfg.sut.runtimeInfo.isJvm then profilingLauncher else sutCommand;
        Environment = cfg.sut.environment;
        User = "sut";
        StateDirectory = "sut";
        StateDirectoryMode = "0755";
        ExecStartPre = lib.optional cfg.profiling.enable (if cfg.sut.runtimeInfo.isNative then nativeProfileCleanup else "${pkgs.coreutils}/bin/rm -f ${profilingPath}");
        ExecStopPost = lib.optional (cfg.profiling.enable && cfg.sut.runtimeInfo.isNative) nativeProfileFinalizer;
        Restart = "no";
        StandardOutput = "journal";
        StandardError = "journal";
        LimitNOFILE = 65536;
        TimeoutStartSec = 130;
      } // lib.optionalAttrs (cfg.profiling.enable && profilingTool == "py-spy") {
        KillSignal = "SIGINT";
      };
    };
  }];
}
