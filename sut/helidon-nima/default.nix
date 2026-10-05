{ config, lib, pkgs, ... }:
let
  runtimeInfo = config.benchmark.sut.runtimeInfo;
  tls = import ../../nix/tls.nix { inherit pkgs; };
  package = pkgs.maven.buildMavenPackage {
    pname = "helidon-nima";
    version = "1.0.0";

    src = pkgs.lib.fileset.toSource {
      root = ./.;
      fileset = pkgs.lib.fileset.unions [
        ./pom.xml
        ./src
      ];
    };

    mvnHash = "sha256-sXUPqKGKpRu3BdGh6duJ0eYvnmBi7cNSYVxSbv4ow7Y=";

    mvnJdk = runtimeInfo.buildPackage;

    nativeBuildInputs = [
      pkgs.makeWrapper
    ];

    doCheck = false;

    preBuild = ''
      install -Dm644 ${tls}/server.pem src/test/resources/benchmark-tls/server.pem
      install -Dm644 ${tls}/server-key.pem src/test/resources/benchmark-tls/server-key.pem
    '';

    installPhase = ''
      runHook preInstall
      install -Dm444 target/helidon-nima.jar "$out/share/helidon-nima/helidon-nima.jar"
      cp -r target/libs "$out/share/helidon-nima/libs"
      makeWrapper ${runtimeInfo.buildPackage}/bin/java "$out/bin/helidon-nima" \
        --prefix PATH : ${pkgs.lib.makeBinPath [ pkgs.systemd ]} \
        --add-flags "-jar $out/share/helidon-nima/helidon-nima.jar"
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
      executable = "helidon-nima";
      description = "Helidon Nima benchmark server";
      metadata = {
        typePrefix = "helidon-nima";
      };
    };
  };
}
