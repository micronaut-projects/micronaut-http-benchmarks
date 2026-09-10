{ config, pkgs, ... }:
let
  runtime = config.benchmark.sut.runtime;
  pgoDirectory = if config.benchmark.sut.pgoBuildDirectory == null then config.benchmark.sut.pgoDirectory else config.benchmark.sut.pgoBuildDirectory;
  runtimeInfo = config.benchmark.sut.runtimeInfo;
  tls = import ../../nix/tls.nix { inherit pkgs; };
  package = pkgs.maven.buildMavenPackage {
    pname = "quarkus-${runtime}";
    version = "1.0.0";

    src = pkgs.lib.fileset.toSource {
      root = ./.;
      fileset = pkgs.lib.fileset.unions [
        ./pom.xml
        ./src
      ];
    };

    mvnHash = "sha256-Rp1lib42AaivT7PlIoxWL3O7NS1fTgOfPSHHNHBHA/M=";

    mvnJdk = runtimeInfo.buildPackage;
    dontStrip = runtimeInfo.keepDebugSymbols;

    mvnParameters = builtins.concatStringsSep " " ([ ]
      ++ pkgs.lib.optionals (runtime != "hotspot") [ "-Pnative" ]
      ++ pkgs.lib.optionals (runtime != "hotspot") [ "-Dquarkus.native.additional-build-args=${builtins.concatStringsSep "," (runtimeInfo.nativeImageArgs
        ++ pkgs.lib.optionals (runtime == "native-pgo-instrument") [ "--pgo-instrument" ]
        ++ pkgs.lib.optionals (runtime == "native-pgo") [ "--pgo=${pgoDirectory}/default.iprof" ])}" ]);

    nativeBuildInputs = [
      pkgs.makeWrapper
    ] ++ pkgs.lib.optional (config.benchmark.sut.pgoBuildDirectory != null) config.benchmark.sut.pgoBuildDirectory;

    __noChroot = runtime == "native-pgo" && config.benchmark.sut.pgoBuildDirectory == null;

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
      executable = "quarkus";
      description = "Quarkus${if runtimeInfo.isJvm then "" else if runtimeInfo.isPgo then " PGO" else " native"} benchmark server";
      metadata = {
        typePrefix = "quarkus";
      };
    };
  };
}
