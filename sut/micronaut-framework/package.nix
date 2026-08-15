{ lib
, maven
, makeWrapper
, jdk25_headless
, systemd
, codec
}:
maven.buildMavenPackage {
  pname = "micronaut-framework-${codec}";
  version = "1.0.0";

  src = lib.fileset.toSource {
    root = ./.;
    fileset = lib.fileset.unions [
      ./pom.xml
      ./src
    ];
  };

  mvnHash = {
    jackson-databind = "sha256-BD+X6PKbj1IDWImDJDiMF2E/mDYKAB4ey/B4wPnXcOw=";
    micronaut-serialization = "sha256-g5TX32Gp88iZDJC9Y6+GMzpxCjABl9jCfkHul1JH64o=";
  }.${codec};

  mvnParameters = "-P${codec}";

  mvnJdk = jdk25_headless;

  nativeBuildInputs = [
    makeWrapper
  ];

  doCheck = false;

  installPhase = ''
    runHook preInstall
    install -Dm444 target/micronaut-framework.jar "$out/share/micronaut-framework/micronaut-framework.jar"
    cp -r target/libs "$out/share/micronaut-framework/libs"
    makeWrapper ${jdk25_headless}/bin/java "$out/bin/micronaut-framework" \
      --prefix PATH : ${lib.makeBinPath [ systemd ]} \
      --add-flags "-jar $out/share/micronaut-framework/micronaut-framework.jar"
    runHook postInstall
  '';
}
