{ config, pkgs, ... }:
let
  runtime = config.benchmark.sut.runtime;
  pgoDirectory = config.benchmark.sut.pgoDirectory;
  runtimeInfo = config.benchmark.sut.runtimeInfo;
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

    mvnHash = "sha256-xVJKLzUwsFhRlLRJo7sMsQWKkYJgKD7iOoBoC+1xEv4=";

    mvnJdk = runtimeInfo.buildPackage;

    mvnParameters = builtins.concatStringsSep " " ([ ]
      ++ pkgs.lib.optionals (runtime != "hotspot") [ "-Pnative" ]
      ++ pkgs.lib.optionals (runtime == "native-pgo-instrument") [ "-Dquarkus.native.additional-build-args=--pgo-instrument" ]
      ++ pkgs.lib.optionals (runtime == "native-pgo") [ "-Dquarkus.native.additional-build-args=--pgo=${pgoDirectory}/default.iprof" ]);

    nativeBuildInputs = [
      pkgs.makeWrapper
    ];

    __noChroot = runtime == "native-pgo";

    doCheck = false;

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
      environment = [ "BENCHMARK_SYSTEMD_READINESS_ENABLED=true" ];
      metadata = {
        typePrefix = "quarkus";
      };
    };
  };
}
