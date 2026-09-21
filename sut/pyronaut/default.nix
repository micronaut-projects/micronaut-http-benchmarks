{ config, lib, pkgs, ... }:
let
  threading = config.pyronaut.threading;
  configureThreading = lib.optionalString (threading != "default") ''
    substituteInPlace benchmark-app/config/application.toml \
      --replace-fail '[micronaut.server]' $'[micronaut.server]\nthread-selection = "BLOCKING"'
    ${lib.optionalString (threading == "loom-carrier") ''
      substituteInPlace benchmark-app/config/application.toml \
        --replace-fail '[micronaut.server]' $'[micronaut.netty.event-loops.default]\nloom-carrier = true\n\n[micronaut.server]'
    ''}
  '';
  tls = import ../../nix/tls.nix { inherit pkgs; };
  graalvm = pkgs.stdenvNoCC.mkDerivation {
    pname = "graalvm-oracle";
    version = "25.3.4.1";
    src = pkgs.fetchurl {
      url = "https://gds.oracle.com/download/graal/25i3/archive/graalvm-jdk-25i3-25.0.4.1_linux-x64_bin.tar.gz";
      hash = "sha256-gU3qwUSpEgNcToJOBfoGouDIOCHw+50QhLyRbl7u8kc=";
    };
    nativeBuildInputs = [ pkgs.autoPatchelfHook ];
    buildInputs = [ pkgs.stdenv.cc.cc.lib pkgs.zlib ];
    autoPatchelfIgnoreMissingDeps = true;
    installPhase = ''
      mkdir -p "$out"
      cp -r . "$out"
    '';
  };
  gradle = pkgs.gradle_9.override {
    java = graalvm;
    javaToolchains = [ graalvm ];
  };
  pyronautPython = pkgs.python3.withPackages (pythonPackages: [
    pythonPackages.pip
    pythonPackages.setuptools
    pythonPackages.wheel
  ]);
  appSource = lib.fileset.toSource {
    root = ./app;
    fileset = lib.fileset.unions [
      ./app/pyproject.toml
      ./app/src
      ./app/config
    ];
  };
  upstream = builtins.fetchGit {
    url = "ssh://git@github.com/micronaut-projects/pyronaut.git";
    rev = "cb4fd7c31620697743f2cb1d035b27c06ef847c3";
  };
  micronautCore = pkgs.fetchFromGitHub {
    owner = "micronaut-projects";
    repo = "micronaut-core";
    rev = "dfface2cc2178afdc141f21258af9d09d271de3f";
    hash = "sha256-QR60jmPUqsVHFQPiv59wOXDKnJLXXEhTX+pksuzDVRw=";
  };
  wheelGradleInit = pkgs.writeText "pyronaut-wheel.init.gradle" ''
    gradle.beforeProject { project ->
        project.repositories {
            mavenLocal()
            mavenCentral()
            maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
        }
        project.configurations.configureEach {
            resolutionStrategy.useGlobalDependencySubstitutionRules.set(false)
        }
    }
  '';
  patchedUpstream = pkgs.applyPatches {
    name = "pyronaut-patched";
    src = upstream;
    postPatch = ''
      substituteInPlace settings.gradle.kts \
        --replace-fail 'micronautBuild {' $'micronautBuild {\n    requiresDevelopmentVersion("micronaut-core", "5.2.x")'
    '';
  };
  runtime = config.benchmark.sut.runtime;
  isJvm = runtime == "hotspot";
  buildTarget = if isJvm then "jvm" else "native";
  nativeImageArgs = config.benchmark.sut.runtimeInfo.nativeImageArgs ++ [
    "--gc=G1"
    "-R:MaxHeapSize=12g"
    "-H:-GraalJITCompileAtRuntime"
    "-H:-RuntimeClassLoading"
  ];
  pythonSitePackages = "lib/python${pyronautPython.pythonVersion}/site-packages";
  pyronaut = pkgs.stdenvNoCC.mkDerivation (finalAttrs: {
    pname = "pyronaut";
    version = "cb4fd7c31620697743f2cb1d035b27c06ef847c3";
    src = patchedUpstream;

    nativeBuildInputs = [ gradle pyronautPython pkgs.cacert pkgs.gnutar pkgs.stdenv.cc ];

    mitmCache = gradle.fetchDeps {
      pkg = finalAttrs.finalPackage;
      data = ./deps.json;
      silent = false;
      useBwrap = false;
    };

    SSL_CERT_FILE = "${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt";
    NIX_SSL_CERT_FILE = "${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt";
    __structuredAttrs = true;

    gradleUpdateScript = ''
      export out="$PWD/pyronaut.tar"
      ${finalAttrs.buildPhase}
    '';

    buildPhase = ''
      runHook preBuild
      export HOME="$TMPDIR/home"
      export TMPDIR="$TMPDIR/tmp"
      export GRADLE_USER_HOME="$TMPDIR/gradle"
      export JAVA_HOME=${graalvm}
      export PYRONAUT_PYTHON_EXECUTABLE=${pyronautPython}/bin/python
      export PIP_NO_BUILD_ISOLATION=0
      export PATH="$JAVA_HOME/bin:$PATH"
      export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=$MITM_CACHE_HOST -Dhttp.proxyPort=$MITM_CACHE_PORT -Dhttps.proxyHost=$MITM_CACHE_HOST -Dhttps.proxyPort=$MITM_CACHE_PORT -Djavax.net.ssl.trustStore=$MITM_CACHE_KEYSTORE -Djavax.net.ssl.trustStorePassword=$MITM_CACHE_KS_PWD"
      mkdir -p "$HOME" "$TMPDIR" "$GRADLE_USER_HOME" "$TMPDIR/sdk" "$TMPDIR/m2"
      cp -r ${micronautCore} "$TMPDIR/micronaut-core"
      chmod -R u+w "$TMPDIR/micronaut-core"
      cat > "$TMPDIR/micronaut-core/gradlew" <<'EOF'
      #!/bin/sh
      exec ${gradle}/bin/gradle "$@"
      EOF
      chmod +x "$TMPDIR/micronaut-core/gradlew"
      gradle \
        -p "$TMPDIR/micronaut-core" \
        publishToMavenLocal \
        --no-daemon \
        --max-workers 4 \
        -Poverride.libs.managed-graal=25.3.4.1 \
        -Dmaven.repo.local="$TMPDIR/m2"
      gradle \
        :micronaut-pyronaut:prepareSdkMavenLocalEnvironment \
        :micronaut-pyronaut-dev:assemble \
        :micronaut-pyronaut-run:assemble \
        :micronaut-pyronaut-run-python:assemble \
        --no-daemon \
        --max-workers 4 \
        -Plocal.git.micronaut-core="$TMPDIR/micronaut-core" \
        -PpyronautNativeImageCiArgs=-H:NativeLinkerOption=-L${pkgs.zlib.static}/lib \
        -Poverride.libs.managed-graal=25.3.4.1 \
        -Dmaven.repo.local="$TMPDIR/m2" \
        -Ppyronaut.sdk.mavenLocalRepository="$TMPDIR/m2"
      gradle \
        :micronaut-pyronaut:buildSdkWheel \
        --no-daemon \
        --max-workers 4 \
        --init-script ${wheelGradleInit} \
        -Plocal.git.micronaut-core="$TMPDIR/micronaut-core" \
        -Poverride.libs.managed-graal=25.3.4.1 \
        -Dmaven.repo.local="$TMPDIR/m2"
      ${pyronautPython}/bin/python -m pip install --no-deps --prefix "$TMPDIR/sdk" pyronaut/build/wheel/dist/pyronaut-*.whl
      mkdir -p "$HOME/.pyronaut"
      cat > "$HOME/.pyronaut/settings.toml" <<EOF
      [native-images]
      base-url = "$PWD"
      version = "0.0.2-SNAPSHOT"
      EOF
      export PYTHONPATH="$TMPDIR/sdk/lib/python${pyronautPython.pythonVersion}/site-packages"
      "$TMPDIR/sdk/bin/pyronaut" setup \
        --local-repository "$TMPDIR/m2" \
        --progress off
      export PYRONAUT_LOCAL_REPOSITORY="$TMPDIR/m2"
      cp -r ${appSource} benchmark-app
      chmod -R u+w benchmark-app
      ${configureThreading}install -Dm644 ${tls}/server.p12 benchmark-app/config/server.p12
      "$TMPDIR/sdk/bin/pyronaut" install \
        --project-dir "$PWD/benchmark-app" \
        --local-repository "$TMPDIR/m2"
      "$TMPDIR/sdk/bin/pyronaut" build \
        --native \
        --project-dir "$PWD/benchmark-app" \
        -- \
        --no-sbom \
        -H:NativeLinkerOption=-L${pkgs.zlib.static}/lib ${lib.concatMapStringsSep " " lib.escapeShellArg nativeImageArgs}
      cp -r benchmark-app/__pyronaut__/reachability-metadata "$TMPDIR/reachability-metadata"
      cp -r "$HOME/.pyronaut" "$TMPDIR/pyronaut-home"
      rm -rf benchmark-app
      find "$TMPDIR/m2" -type f \( -name '*.lastUpdated' -o -name '_remote.repositories' -o -name 'resolver-status.properties' \) -delete
      tar --sort=name --mtime=@1 --owner=0 --group=0 --numeric-owner \
        -cf "$out" -C "$TMPDIR" sdk m2 pyronaut-home reachability-metadata
      runHook postBuild
    '';

    installPhase = "true";
  });
  sut = pkgs.stdenvNoCC.mkDerivation {
    pname = "pyronaut-benchmark-${buildTarget}";
    version = "cb4fd7c31620697743f2cb1d035b27c06ef847c3";
    src = patchedUpstream;

    nativeBuildInputs = [ pkgs.gnutar pkgs.makeWrapper pkgs.stdenv.cc ];
    dontStrip = config.benchmark.sut.runtimeInfo.keepDebugSymbols;

    buildPhase = ''
      runHook preBuild
      export HOME="$TMPDIR/home"
      export TMPDIR="$TMPDIR/tmp"
      export JAVA_HOME=${graalvm}
      export PATH="$JAVA_HOME/bin:$PATH"
      mkdir -p "$HOME" "$TMPDIR"
      mkdir "$TMPDIR/pyronaut"
      tar -xf ${pyronaut} -C "$TMPDIR/pyronaut"
      chmod -R u+w "$TMPDIR/pyronaut"
      cp -r "$TMPDIR/pyronaut/pyronaut-home" "$HOME/.pyronaut"
      export PYTHONPATH="$TMPDIR/pyronaut/sdk/lib/python${pyronautPython.pythonVersion}/site-packages"
      ${pyronautPython}/bin/python "$TMPDIR/pyronaut/sdk/bin/pyronaut" setup \
        --offline \
        --refresh \
        --local-repository "$TMPDIR/pyronaut/m2" \
        --progress off
      cp -r ${appSource} benchmark-app
      chmod -R u+w benchmark-app
      ${configureThreading}install -Dm644 ${tls}/server.p12 benchmark-app/config/server.p12
      ${pyronautPython}/bin/python "$TMPDIR/pyronaut/sdk/bin/pyronaut" install \
        --offline \
        --project-dir "$PWD/benchmark-app" \
        --local-repository "$TMPDIR/pyronaut/m2"
      ${lib.optionalString (!isJvm) ''
        mkdir -p benchmark-app/__pyronaut__
        cp -r "$TMPDIR/pyronaut/reachability-metadata" benchmark-app/__pyronaut__/reachability-metadata
      ''}
      export PYRONAUT_LOCAL_REPOSITORY="$TMPDIR/pyronaut/m2"
      ${if isJvm then ''
        ${pyronautPython}/bin/python "$TMPDIR/pyronaut/sdk/bin/pyronaut" build \
          --jvm \
          --offline \
          --project-dir "$PWD/benchmark-app"
      '' else ''
        ${pyronautPython}/bin/python "$TMPDIR/pyronaut/sdk/bin/pyronaut" build \
          --native \
          --offline \
          --project-dir "$PWD/benchmark-app" \
          -- \
          --no-sbom \
          -H:NativeLinkerOption=-L${pkgs.zlib.static}/lib ${lib.concatMapStringsSep " " lib.escapeShellArg nativeImageArgs}
      ''}
      test -f benchmark-app/dist/pyronaut_benchmark-*.whl
      runHook postBuild
    '';

    installPhase = ''
      runHook preInstall
      ${pyronautPython}/bin/python -m pip install --no-deps --prefix "$out" benchmark-app/dist/pyronaut_benchmark-*.whl
      ${lib.optionalString isJvm ''
        install -d "$out/${pythonSitePackages}"
        cp -r "$TMPDIR/pyronaut/sdk/${pythonSitePackages}/pyronaut_cli_v2" "$out/${pythonSitePackages}"
        cp -r "$TMPDIR/pyronaut/sdk/${pythonSitePackages}"/pyronaut-*.dist-info "$out/${pythonSitePackages}"
      ''}
      rm -f "$out/bin/pyronaut"
      install -Dm755 ${./run} "$out/libexec/pyronaut"
      makeWrapper ${pkgs.bash}/bin/bash "$out/bin/pyronaut" \
        --add-flags "$out/libexec/pyronaut" \
        --set PYRONAUT_DEPLOYABLE "$out/bin/pyronaut-benchmark" \
        --set PYTHONPATH "$out/${pythonSitePackages}" \
        --set CURL ${pkgs.curl}/bin/curl \
        ${lib.optionalString isJvm "--set JAVA_HOME ${graalvm}"} --set HOME /var/lib/sut \
        --prefix PATH : ${lib.makeBinPath ((lib.optional isJvm graalvm) ++ [ pkgs.coreutils pkgs.systemd ])}
      runHook postInstall
    '';

    passthru.pyronaut = pyronaut;
  };
in {
  options.pyronaut.threading = lib.mkOption {
    type = lib.types.enum [ "default" "virtual" "loom-carrier" ];
    default = "default";
    description = "Server threading mode; virtual and loom-carrier use BLOCKING thread selection.";
  };
  config = {
    benchmark = {
      sut.tlsHttp2 = true;
      jvm = {
        enable = isJvm;
        extraArgs = lib.optional isJvm "-Dpolyglot.engine.userResourceCache=/var/lib/sut/.cache/org.graalvm.polyglot"
          ++ lib.optional (isJvm && threading == "loom-carrier") "--add-opens=java.base/java.lang=ALL-UNNAMED";
      };
      sut = {
        package = sut;
        executable = "pyronaut";
        description = "Pyronaut ${if isJvm then "JVM" else "native"} benchmark server";
        environment = [ "XDG_CACHE_HOME=/var/lib/sut/.cache" ];
        metadata = {
          typePrefix = "pyronaut";
          parameters.threading = threading;
        };
      };
    };
    systemd.services.sut.serviceConfig = {
      TimeoutStartSec = lib.mkForce 600;
      WorkingDirectory = "/var/lib/sut";
    };
  };
}
