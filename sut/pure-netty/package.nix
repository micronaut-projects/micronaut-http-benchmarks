{ lib
, maven
, makeWrapper
, jdk25_headless
, systemd
}:
maven.buildMavenPackage {
  pname = "pure-netty";
  version = "1.0.0";

  src = lib.fileset.toSource {
    root = ./.;
    fileset = lib.fileset.unions [
      ./pom.xml
      ./src
    ];
  };

  mvnHash = "sha256-JOXrtGDOPFLJ2WXaaEdcavo9H2r6OJAlgvDcduV45Tk=";

  mvnJdk = jdk25_headless;

  nativeBuildInputs = [
    makeWrapper
  ];

  doCheck = false;

  installPhase = ''
    runHook preInstall
    install -Dm444 target/pure-netty.jar "$out/share/pure-netty/pure-netty.jar"
    makeWrapper ${jdk25_headless}/bin/java "$out/bin/pure-netty" \
      --prefix PATH : ${lib.makeBinPath [ systemd ]} \
      --add-flags "-jar $out/share/pure-netty/pure-netty.jar"
    runHook postInstall
  '';
}
