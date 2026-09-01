{ config, lib, pkgs, ... }:
let
  runtimeInfo = config.benchmark.sut.runtimeInfo;
  tls = import ../../nix/tls.nix { inherit pkgs; };
  package = pkgs.maven.buildMavenPackage {
    pname = "spring-boot";
    version = "1.0.0";

    src = pkgs.lib.fileset.toSource {
      root = ./.;
      fileset = pkgs.lib.fileset.unions [
        ./pom.xml
        ./src
      ];
    };

    mvnHash = "sha256-eD8bYSbzCcJvfMu7T8Zos4lvkB2cxhHK4MvPv9A/rBs=";

    mvnJdk = runtimeInfo.buildPackage;

    nativeBuildInputs = [
      pkgs.makeWrapper
    ];

    doCheck = false;
    preBuild = ''
      install -Dm644 ${tls}/server.p12 src/main/resources/keys.p12
    '';

    installPhase = ''
      runHook preInstall
      install -Dm444 target/spring-boot.jar "$out/share/spring-boot/spring-boot.jar"
      makeWrapper ${runtimeInfo.buildPackage}/bin/java "$out/bin/spring-boot" \
        --prefix PATH : ${pkgs.lib.makeBinPath [ pkgs.systemd ]} \
        --add-flags "-jar $out/share/spring-boot/spring-boot.jar"
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
      executable = "spring-boot";
      description = "Spring Boot benchmark server";
      environment = [ "SPRING_SYSTEMD_NOTIFY_ENABLED=true" ];
      metadata = {
        typePrefix = "spring-boot";
      };
    };
  };
}
