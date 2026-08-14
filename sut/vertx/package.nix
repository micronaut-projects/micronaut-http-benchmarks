{ lib
, maven
, makeWrapper
, jdk25_headless
, systemd
}:
maven.buildMavenPackage {
  pname = "vertx";
  version = "1.0.0";

  src = lib.fileset.toSource {
    root = ./.;
    fileset = lib.fileset.unions [
      ./pom.xml
      ./src
    ];
  };

  mvnHash = "sha256-YqLGp8+747/Wq5X6gDcRsh8pGnkmeik+eJ/Rb0ODoIE=";

  mvnJdk = jdk25_headless;

  nativeBuildInputs = [
    makeWrapper
  ];

  doCheck = false;

  installPhase = ''
    runHook preInstall
    install -Dm444 target/vertx.jar "$out/share/vertx/vertx.jar"
    cp -r target/libs "$out/share/vertx/libs"
    makeWrapper ${jdk25_headless}/bin/java "$out/bin/vertx" \
      --prefix PATH : ${lib.makeBinPath [ systemd ]} \
      --add-flags "--enable-native-access=ALL-UNNAMED" \
      --add-flags "-jar $out/share/vertx/vertx.jar"
    runHook postInstall
  '';
}
