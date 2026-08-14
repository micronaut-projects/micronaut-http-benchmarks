{ lib
, maven
, makeWrapper
, jdk25_headless
, systemd
}:
maven.buildMavenPackage {
  pname = "micronaut-framework";
  version = "1.0.0";

  src = lib.fileset.toSource {
    root = ./.;
    fileset = lib.fileset.unions [
      ./pom.xml
      ./src
    ];
  };

  mvnHash = "sha256-eU0CIT/WDGiJoDksI/u1wuuwBjy4GvYXPRAjeGHpAaY=";

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
