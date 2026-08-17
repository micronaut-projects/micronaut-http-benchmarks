{ lib
, stdenvNoCC
, gradle_9
, makeWrapper
, jdk25_headless
, systemd
, codec
}:
let
  gradle = gradle_9.override {
    javaToolchains = [ jdk25_headless ];
  };
in
stdenvNoCC.mkDerivation (finalAttrs: {
  pname = "micronaut-framework-${codec}";
  version = "1.0.0";

  src = lib.fileset.toSource {
    root = ./.;
    fileset = lib.fileset.unions [
      ./settings.gradle.kts
      ./build.gradle.kts
      ./gradle
      ./src
    ];
  };

  nativeBuildInputs = [
    gradle
    makeWrapper
  ];

  mitmCache = gradle.fetchDeps {
    pkg = finalAttrs.finalPackage;
    data = ./deps.json;
    useBwrap = false;
  };

  gradleBuildTask = "jar";
  gradleUpdateScript = ''
    gradle ${finalAttrs.gradleBuildTask} -Pcodec=jackson-databind
    gradle ${finalAttrs.gradleBuildTask} -Pcodec=micronaut-serialization
  '';
  gradleFlags = [ "-Pcodec=${codec}" ];

  doCheck = false;

  installPhase = ''
    runHook preInstall
    install -Dm444 build/libs/micronaut-framework.jar "$out/share/micronaut-framework/micronaut-framework.jar"
    cp -r build/libs/libs "$out/share/micronaut-framework/libs"
    makeWrapper ${jdk25_headless}/bin/java "$out/bin/micronaut-framework" \
      --prefix PATH : ${lib.makeBinPath [ systemd ]} \
      --add-flags "-jar $out/share/micronaut-framework/micronaut-framework.jar"
    runHook postInstall
  '';
})
