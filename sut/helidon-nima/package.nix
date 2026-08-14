{ lib
, maven
, makeWrapper
, jdk25_headless
, systemd
}:
maven.buildMavenPackage {
  pname = "helidon-nima";
  version = "1.0.0";

  src = lib.fileset.toSource {
    root = ./.;
    fileset = lib.fileset.unions [
      ./pom.xml
      ./src
    ];
  };

  mvnHash = "sha256-sXUPqKGKpRu3BdGh6duJ0eYvnmBi7cNSYVxSbv4ow7Y=";

  mvnJdk = jdk25_headless;

  nativeBuildInputs = [
    makeWrapper
  ];

  doCheck = false;

  installPhase = ''
    runHook preInstall
    install -Dm444 target/helidon-nima.jar "$out/share/helidon-nima/helidon-nima.jar"
    cp -r target/libs "$out/share/helidon-nima/libs"
    makeWrapper ${jdk25_headless}/bin/java "$out/bin/helidon-nima" \
      --prefix PATH : ${lib.makeBinPath [ systemd ]} \
      --add-flags "-jar $out/share/helidon-nima/helidon-nima.jar"
    runHook postInstall
  '';
}
