{ lib
, maven
, makeWrapper
, jdk25_headless
, systemd
}:
maven.buildMavenPackage {
  pname = "quarkus";
  version = "1.0.0";

  src = lib.fileset.toSource {
    root = ./.;
    fileset = lib.fileset.unions [
      ./pom.xml
      ./src
    ];
  };

  mvnHash = "sha256-xVJKLzUwsFhRlLRJo7sMsQWKkYJgKD7iOoBoC+1xEv4=";

  mvnJdk = jdk25_headless;

  nativeBuildInputs = [
    makeWrapper
  ];

  doCheck = false;

  installPhase = ''
    runHook preInstall
    mkdir -p "$out/share"
    cp -r target/quarkus-app "$out/share/quarkus-app"
    makeWrapper ${jdk25_headless}/bin/java "$out/bin/quarkus" \
      --prefix PATH : ${lib.makeBinPath [ systemd ]} \
      --add-flags "-jar $out/share/quarkus-app/quarkus-run.jar"
    runHook postInstall
  '';
}
