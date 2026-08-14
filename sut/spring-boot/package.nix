{ lib
, maven
, makeWrapper
, jdk25_headless
, systemd
}:
maven.buildMavenPackage {
  pname = "spring-boot";
  version = "1.0.0";

  src = lib.fileset.toSource {
    root = ./.;
    fileset = lib.fileset.unions [
      ./pom.xml
      ./src
    ];
  };

  mvnHash = "sha256-eD8bYSbzCcJvfMu7T8Zos4lvkB2cxhHK4MvPv9A/rBs=";

  mvnJdk = jdk25_headless;

  nativeBuildInputs = [
    makeWrapper
  ];

  doCheck = false;

  installPhase = ''
    runHook preInstall
    install -Dm444 target/spring-boot.jar "$out/share/spring-boot/spring-boot.jar"
    makeWrapper ${jdk25_headless}/bin/java "$out/bin/spring-boot" \
      --prefix PATH : ${lib.makeBinPath [ systemd ]} \
      --add-flags "-jar $out/share/spring-boot/spring-boot.jar"
    runHook postInstall
  '';
}
