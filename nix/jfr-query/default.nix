{ pkgs }:
pkgs.maven.buildMavenPackage {
  pname = "jfr-query";
  version = "0-unstable-2026-09-07";
  src = pkgs.fetchFromGitHub {
    owner = "parttimenerd";
    repo = "jfr-query";
    rev = "082902d821034eea35bafda21bc420ba8ad1cd72";
    hash = "sha256-DIOetuWvO4VnvfF4Xs7LP1Q2XRrP54oQs3gRD1uz3BQ=";
  };
  # Restore the parent POM from upstream d1b2046b62f7f384a0438cce5dd27e61dc89f548.
  # The current root POM builds the obsolete pre-DuckDB application instead of core/.
  postPatch = ''
    cp ${./pom.xml} pom.xml
    # The frontend is intentionally not built; advertise only the available CLI commands.
    substituteInPlace core/src/main/java/me/bechberger/jfr/duckdb/Main.java \
      --replace-fail "            ServeCommand.class," ""
  '';
  # Use the public null, array, and timestamp APIs of the pinned JDBC dependency.
  patches = [ ./jdbc-appender.patch ];
  mvnJdk = pkgs.jdk25;
  mvnHash = "sha256-g/b5uYpN4KXAkc3myk8ylzmnttkABWZJPwuP9xDJa5E=";
  # Package the CLI; the optional browser frontend requires a separate npm build.
  mvnParameters = "-pl core -am -Dmaven.test.skip=true -Dexec.skip=true";
  nativeBuildInputs = [ pkgs.makeWrapper ];
  installPhase = ''
    runHook preInstall
    install -Dm444 core/target/query.jar $out/share/java/jfr-query.jar
    makeWrapper ${pkgs.jdk25}/bin/java $out/bin/jfr-query \
      --prefix LD_LIBRARY_PATH : ${pkgs.lib.makeLibraryPath [ pkgs.stdenv.cc.cc.lib ]} \
      --add-flags "--enable-native-access=ALL-UNNAMED -jar $out/share/java/jfr-query.jar"
    runHook postInstall
  '';
  meta = {
    description = "SQL queries over Java Flight Recorder recordings using DuckDB (CLI)";
    homepage = "https://github.com/parttimenerd/jfr-query";
    license = pkgs.lib.licenses.gpl2Only;
    platforms = [ "x86_64-linux" "aarch64-linux" ];
    mainProgram = "jfr-query";
  };
}
