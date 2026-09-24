{ config, lib, pkgs, ... }:
let
  codec = "micronaut-serialization";
  threading = config.micronaut-framework.threading;
  runtime = config.benchmark.sut.runtime;
  runtimeInfo = config.benchmark.sut.runtimeInfo;
  tls = import ../../nix/tls.nix { inherit pkgs; };
  upstream = builtins.fetchGit {
    url = "https://github.com/micronaut-projects/micronaut-core.git";
    ref = "5.3.x";
    rev = "733ff0ded334bd3f0e4d11e254e6f539e6d50bbe";
  };
  package =
    assert lib.assertMsg (runtime != "native-pgo" || config.benchmark.sut.pgoProfile != null)
      "Micronaut native-pgo requires a build-time training profile.";
    let
      gradle = pkgs.gradle_9.override {
        java = runtimeInfo.buildPackage;
        javaToolchains = [ runtimeInfo.buildPackage ];
      };
    in
    pkgs.stdenvNoCC.mkDerivation (finalAttrs: {
      pname = "micronaut-framework-${codec}-${runtime}";
      version = upstream.rev;

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

      # Compile the pinned core sources through Gradle's composite substitution,
      # including transitive core modules and the annotation processors.
      postPatch = ''
        cp -r ${upstream} micronaut-core
        chmod -R u+w micronaut-core
        cat >> settings.gradle.kts <<'EOF'

        includeBuild("micronaut-core")
        EOF
      '';

      gradleBuildTask = if runtime == "hotspot" then "jar" else "nativeCompile";
      dontStrip = runtimeInfo.keepDebugSymbols;
      nativeGradleFlags = lib.optionals (runtime != "hotspot") [ "-PnativeBuild" "-PnativeImageArgs=${lib.concatStringsSep "," (runtimeInfo.nativeImageArgs
        ++ lib.optionals (runtime == "native-pgo-instrument") [ "--pgo-instrument" ]
        ++ lib.optionals (runtime == "native-pgo") [ "--pgo=${config.benchmark.sut.pgoProfile}/default.iprof" ])}" ];
      gradleUpdateTaskSuffix = lib.optionalString (runtime != "hotspot") " --dry-run";
      gradleUpdateScript = ''
        gradle nixDownloadDeps ${lib.concatStringsSep " " finalAttrs.nativeGradleFlags}
        # Resolving the app alone misses compile-only dependencies in the included core build.
        gradle jar --max-workers 4 ${lib.concatStringsSep " " finalAttrs.nativeGradleFlags}
        ${lib.optionalString (runtime != "hotspot") ''gradle generateDynamicAccessMetadata ${lib.concatStringsSep " " finalAttrs.nativeGradleFlags}''}
        gradle ${finalAttrs.gradleBuildTask} ${lib.concatStringsSep " " finalAttrs.nativeGradleFlags}${finalAttrs.gradleUpdateTaskSuffix}
      '';
      gradleFlags = finalAttrs.nativeGradleFlags;
      preBuild = ''
        install -Dm644 ${tls}/server.p12 src/main/resources/server.p12
      '' + lib.optionalString (threading != "default") ''
        substituteInPlace src/main/resources/application.yml \
          --replace-fail $'  server:\n' $'  server:\n    thread-selection: BLOCKING\n'
        ${lib.optionalString (threading == "loom-carrier") ''
          substituteInPlace src/main/resources/application.yml \
            --replace-fail $'      default:\n' $'      default:\n        loom-carrier: true\n'
        ''}
      '';
      nativeBuildInputs = [ gradle pkgs.makeWrapper ];
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
      tlsHttp2 = true;
      executable = "micronaut-framework";
      description = if runtimeInfo.isJvm then "Micronaut Framework ${codec} benchmark server" else if runtimeInfo.isPgo then "Micronaut Framework PGO benchmark server" else "Micronaut Framework native benchmark server";
      environment = [ "MICRONAUT_SYSTEMD_NOTIFY_ENABLED=true" ];
      metadata = {
        typePrefix = "micronaut-framework";
        typeSuffix = codec;
        parameters.codec = codec;
        parameters.threading = threading;
        parameters.transport = "io-uring";
        parameters.sourceRevision = upstream.rev;
      };
    };
  };
}
