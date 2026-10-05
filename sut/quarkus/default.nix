{ config, pkgs, ... }:
let
  runtime = config.benchmark.sut.runtime;
  runtimeInfo = config.benchmark.sut.runtimeInfo;
  tls = import ../../nix/tls.nix { inherit pkgs; };
  package =
    assert pkgs.lib.assertMsg (runtime != "native-pgo" || config.benchmark.sut.pgoProfile != null)
      "Quarkus native-pgo requires a build-time training profile.";
    pkgs.maven.buildMavenPackage {
    pname = "quarkus-${runtime}";
    version = "1.0.0";

    src = pkgs.lib.fileset.toSource {
      root = ./.;
      fileset = pkgs.lib.fileset.unions [
        ./pom.xml
        ./src
      ];
    };

    # Resolve Maven plugins and Quarkus deployment dependencies without building
    # a native image. All native modes share this profile-independent cache.
    buildOffline = true;
    mvnDepsParameters = "-Pnative quarkus:go-offline";
    mvnFetchExtraArgs.pname = "maven-deps-quarkus";
    mvnHash = "sha256-+U4NHWGlS4mayN4Xcn7OoA4XA41HUvnRFm/rqRkWdT4=";

    mvnJdk = runtimeInfo.buildPackage;
    dontStrip = runtimeInfo.keepDebugSymbols;

    mvnParameters = builtins.concatStringsSep " " ([ ]
      ++ pkgs.lib.optionals (runtime != "hotspot") [ "-Pnative" ]
      ++ pkgs.lib.optionals (runtime != "hotspot") [ "-Dquarkus.native.additional-build-args=${builtins.concatStringsSep "," (runtimeInfo.nativeImageArgs
        ++ pkgs.lib.optionals (runtime == "native-pgo-instrument") [ "--pgo-instrument" ]
        ++ pkgs.lib.optionals (runtime == "native-pgo") [ "--pgo=${config.benchmark.sut.pgoProfile}/default.iprof" ])}" ]);

    nativeBuildInputs = [
      pkgs.makeWrapper
    ];

    doCheck = false;
    preBuild = ''
      install -Dm644 ${tls}/server.p12 src/main/resources/keys.p12
    '';

    installPhase = ''
      runHook preInstall
      if [ "${runtime}" = hotspot ]; then
        mkdir -p "$out/share"
        cp -r target/quarkus-app "$out/share/quarkus-app"
        makeWrapper ${runtimeInfo.buildPackage}/bin/java "$out/bin/quarkus" \
          --prefix PATH : ${pkgs.lib.makeBinPath [ pkgs.systemd ]} \
          --add-flags "-jar $out/share/quarkus-app/quarkus-run.jar"
      else
        install -Dm755 target/*-runner "$out/bin/quarkus"
      fi
      runHook postInstall
    '';
  };
in {
  benchmark = {
    jvm.enable = runtimeInfo.isJvm;
    sut = {
      inherit package;
      tlsHttp2 = true;
      executable = "quarkus";
      description = "Quarkus${if runtimeInfo.isJvm then "" else if runtimeInfo.isPgo then " PGO" else " native"} benchmark server";
      metadata = {
        typePrefix = "quarkus";
      };
    };
  };
}
