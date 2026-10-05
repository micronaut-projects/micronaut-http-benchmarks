{ config, lib, pkgs, ... }:
let
  runtimeInfo = config.benchmark.sut.runtimeInfo;
  package = pkgs.maven.buildMavenPackage {
    pname = "vertx";
    version = "1.0.0";

    src = pkgs.lib.fileset.toSource {
      root = ./.;
      fileset = pkgs.lib.fileset.unions [
        ./pom.xml
        ./src
      ];
    };

    mvnHash = "sha256-YqLGp8+747/Wq5X6gDcRsh8pGnkmeik+eJ/Rb0ODoIE=";

    mvnJdk = runtimeInfo.buildPackage;

    nativeBuildInputs = [
      pkgs.makeWrapper
    ];

    doCheck = false;

    installPhase = ''
      runHook preInstall
      install -Dm444 target/vertx.jar "$out/share/vertx/vertx.jar"
      cp -r target/libs "$out/share/vertx/libs"
      makeWrapper ${runtimeInfo.buildPackage}/bin/java "$out/bin/vertx" \
        --prefix PATH : ${pkgs.lib.makeBinPath [ pkgs.systemd ]} \
        --add-flags "--enable-native-access=ALL-UNNAMED" \
        --add-flags "-jar $out/share/vertx/vertx.jar"
      runHook postInstall
    '';
  };
in
{
  benchmark = {
    sut.runtime = lib.mkForce "hotspot";
    jvm.enable = true;
    sut = {
      inherit package;
      tlsHttp2 = true;
      executable = "vertx";
      description = "Vert.x benchmark server";
      metadata = {
        typePrefix = "vertx";
      };
    };
  };
}
