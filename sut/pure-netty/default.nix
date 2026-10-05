{ config, lib, pkgs, ... }:
let
  runtimeInfo = config.benchmark.sut.runtimeInfo;
  package = pkgs.maven.buildMavenPackage {
    pname = "pure-netty";
    version = "1.0.0";

    src = pkgs.lib.fileset.toSource {
      root = ./.;
      fileset = pkgs.lib.fileset.unions [
        ./pom.xml
        ./src
      ];
    };

    mvnHash = "sha256-U9tr15a/1CJVgHOWPvEbV1p7nIab1idj5lEHlCJaraI=";

    mvnJdk = runtimeInfo.buildPackage;

    nativeBuildInputs = [
      pkgs.makeWrapper
    ];

    doCheck = false;

    installPhase = ''
      runHook preInstall
      install -Dm444 target/pure-netty.jar "$out/share/pure-netty/pure-netty.jar"
      makeWrapper ${runtimeInfo.buildPackage}/bin/java "$out/bin/pure-netty" \
        --prefix PATH : ${pkgs.lib.makeBinPath [ pkgs.systemd ]} \
        --add-flags "-jar $out/share/pure-netty/pure-netty.jar"
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
      executable = "pure-netty";
      description = "Pure Netty benchmark server";
      metadata = {
        typePrefix = "pure-netty";
      };
    };
  };
}
