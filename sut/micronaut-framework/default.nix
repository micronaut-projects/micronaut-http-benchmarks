{ config, lib, pkgs, ... }:
let
  codec = config.micronaut-framework.codec;
  threading = config.micronaut-framework.threading;
  pgoDirectory = if config.benchmark.sut.pgoBuildDirectory == null then config.benchmark.sut.pgoDirectory else config.benchmark.sut.pgoBuildDirectory;
  runtime = config.benchmark.sut.runtime;
  runtimeInfo = config.benchmark.sut.runtimeInfo;
  tls = import ../../nix/tls.nix { inherit pkgs; };
  package =
    let
      gradle = pkgs.gradle_9.override {
        java = runtimeInfo.buildPackage;
        javaToolchains = [ runtimeInfo.buildPackage ];
      };
    in
    pkgs.stdenvNoCC.mkDerivation (finalAttrs: {
      pname = "micronaut-framework-${codec}-${runtime}";
      version = "1.0.0";

      src = lib.fileset.toSource {
        root = ./.;
        fileset = lib.fileset.unions [
          ./settings.gradle.kts
          ./build.gradle.kts
          ./gradle
          ./src
        ];
      };

      mitmCache = gradle.fetchDeps {
        pkg = finalAttrs.finalPackage;
        data = ./deps.json;
        useBwrap = false;
      };

      gradleBuildTask = if runtime == "hotspot" then "jar" else "nativeCompile";
      dontStrip = runtimeInfo.keepDebugSymbols;
      nativeGradleFlags = lib.optionals (runtime != "hotspot") [ "-PnativeBuild" "-PnativeImageArgs=${lib.concatStringsSep "," (runtimeInfo.nativeImageArgs
        ++ lib.optionals (runtime == "native-pgo-instrument") [ "--pgo-instrument" ]
        ++ lib.optionals (runtime == "native-pgo") [ "--pgo=${pgoDirectory}/default.iprof" ])}" ];
      gradleUpdateTaskSuffix = lib.optionalString (runtime != "hotspot") " --dry-run";
      gradleUpdateScript = ''
        gradle nixDownloadDeps -Pcodec=jackson-databind ${lib.concatStringsSep " " finalAttrs.nativeGradleFlags}
        gradle nixDownloadDeps -Pcodec=micronaut-serialization ${lib.concatStringsSep " " finalAttrs.nativeGradleFlags}
        ${lib.optionalString (runtime != "hotspot") ''gradle generateDynamicAccessMetadata -Pcodec=jackson-databind ${lib.concatStringsSep " " finalAttrs.nativeGradleFlags}''}
        gradle ${finalAttrs.gradleBuildTask} -Pcodec=jackson-databind ${lib.concatStringsSep " " finalAttrs.nativeGradleFlags}${finalAttrs.gradleUpdateTaskSuffix}
        ${lib.optionalString (runtime != "hotspot") ''gradle generateDynamicAccessMetadata -Pcodec=micronaut-serialization ${lib.concatStringsSep " " finalAttrs.nativeGradleFlags}''}
        gradle ${finalAttrs.gradleBuildTask} -Pcodec=micronaut-serialization ${lib.concatStringsSep " " finalAttrs.nativeGradleFlags}${finalAttrs.gradleUpdateTaskSuffix}
      '';
      gradleFlags = [ "-Pcodec=${codec}" ] ++ finalAttrs.nativeGradleFlags;
      preBuild = ''
        install -Dm644 ${tls}/server.p12 src/main/resources/server.p12
      '' + lib.optionalString (threading != "default") ''
        substituteInPlace src/main/resources/application.yml \
          --replace-fail $'  server:\n' $'  server:\n    thread-selection: BLOCKING\n'
        ${lib.optionalString (threading == "loom-carrier") ''
          substituteInPlace src/main/resources/application.yml \
            --replace-fail $'      default:\n        prefer-native-transport: true' $'      default:\n        loom-carrier: true\n        prefer-native-transport: true'
        ''}
      '';
      nativeBuildInputs = [ gradle pkgs.makeWrapper ] ++ lib.optional (config.benchmark.sut.pgoBuildDirectory != null) config.benchmark.sut.pgoBuildDirectory;
      __noChroot = runtime == "native-pgo" && config.benchmark.sut.pgoBuildDirectory == null;
      doCheck = false;

      installPhase = ''
        runHook preInstall
        if [ "${runtime}" = hotspot ]; then
          install -Dm444 build/libs/micronaut-framework.jar "$out/share/micronaut-framework/micronaut-framework.jar"
          cp -r build/libs/libs "$out/share/micronaut-framework/libs"
          makeWrapper ${runtimeInfo.buildPackage}/bin/java "$out/bin/micronaut-framework" \
            --prefix PATH : ${lib.makeBinPath [ pkgs.systemd ]} \
            --add-flags "-jar $out/share/micronaut-framework/micronaut-framework.jar"
        else
          install -Dm755 build/native/nativeCompile/micronaut-framework "$out/bin/micronaut-framework"
        fi
        runHook postInstall
      '';
    });
in {
  options.micronaut-framework.codec = lib.mkOption {
    type = lib.types.enum [ "jackson-databind" "micronaut-serialization" ];
    default = "jackson-databind";
    description = "The Micronaut Framework JSON codec used by this benchmark run.";
  };
  options.micronaut-framework.threading = lib.mkOption {
    type = lib.types.enum [ "default" "virtual" "loom-carrier" ];
    default = "default";
    description = "Server threading mode; virtual and loom-carrier use BLOCKING thread selection.";
  };
  config.benchmark = {
    jvm = {
      enable = runtimeInfo.isJvm;
      extraArgs = [
        "-Djdk.trackAllThreads=false"
        "-XX:+UnlockExperimentalVMOptions"
        "--add-opens=java.base/java.lang=ALL-UNNAMED"
      ];
    };
    sut = {
      inherit package;
      executable = "micronaut-framework";
      description = if runtimeInfo.isJvm then "Micronaut Framework ${codec} benchmark server" else if runtimeInfo.isPgo then "Micronaut Framework PGO benchmark server" else "Micronaut Framework native benchmark server";
      environment = [ "MICRONAUT_SYSTEMD_NOTIFY_ENABLED=true" ];
      metadata = {
        typePrefix = "micronaut-framework";
        typeSuffix = codec;
        parameters.codec = codec;
        parameters.threading = threading;
      };
    };
  };
}
