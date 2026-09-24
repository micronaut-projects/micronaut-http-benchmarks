{ config, lib, pkgs, ... }:
let
  threading = config.pyronaut.threading;
  # Synchronous Python handlers always use the IO executor, regardless of server
  # thread selection. Async handlers keep the work on the receiving event loop.
  configureThreading = lib.optionalString (threading == "event-loop") ''
    substituteInPlace benchmark-app/src/benchmark.py \
      --replace-fail 'def status(' 'async def status(' \
      --replace-fail 'def find(' 'async def find('
  '';
  # Pin and record the context-pool defaults independently of controller threading.
  # Zero means twice the available processors for the shared pool, and one
  # dedicated context per event loop without a cap for async handlers.
  contextPool = {
    enabled = true;
    size = 0;
    max-event-loop-contexts = 0;
  };
  poolConfiguration = (pkgs.formats.toml { }).generate "pyronaut-context-pool.toml" {
    micronaut.python.pool = contextPool;
  };
  tls = import ../../nix/tls.nix { inherit pkgs; };
  graalvm = pkgs.stdenvNoCC.mkDerivation {
    pname = "graalvm-oracle";
    version = "25.4.4.1.1";
    src = pkgs.fetchurl {
      url = "https://gds.oracle.com/download/graal/25i4/archive/graalvm-jdk-25i4-25.0.4.1.1_linux-x64_bin.tar.gz";
      hash = "sha256-T8xjLPxo6Y9J+TFvijWIuv5PUonxIBBOLSkKdc8z4o4=";
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
    ref = "0.0.x";
    rev = "3c54ff1ec4113660b5794a28aea5bc5f748520c7";
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
  runtime = config.benchmark.sut.runtime;
  isJvm = runtime == "hotspot";
  buildTarget = if isJvm then "jvm" else "native";
  nativeImageInitArgs = [
    # Micronaut 5.2 registers this converter in the build-time conversion service.
    "--initialize-at-build-time=io.micronaut.http.server.cors.CorsOriginConverter"
  ];
  baseNativeImageArgs = nativeImageInitArgs ++ [
    "--gc=G1"
    "-R:MaxHeapSize=12g"
    "-H:-GraalJITCompileAtRuntime"
    "-H:-RuntimeClassLoading"
  ];
  # The asyncio bridge converts its Python cancellation callback to Runnable.
  # The pinned runtime does not register this dynamic proxy for native images.
  asyncNativeMetadata = pkgs.writeTextFile {
    name = "pyronaut-async-native-metadata";
    destination = "/reachability-metadata.json";
    text = builtins.toJSON {
      reflection = [{ type.proxy = [ "java.lang.Runnable" ]; }];
    };
  };
  nativeImageArgs = config.benchmark.sut.runtimeInfo.nativeImageArgs ++ baseNativeImageArgs
    ++ lib.optional (threading == "event-loop") "-H:ConfigurationFileDirectories=${asyncNativeMetadata}";
  pythonSitePackages = "lib/python${pyronautPython.pythonVersion}/site-packages";
  # Share the SDK across runtime variants; apply threading and profiling to the SUT below.
  pyronaut = pkgs.stdenvNoCC.mkDerivation (finalAttrs: {
    pname = "pyronaut";
    version = upstream.rev;
    src = upstream;

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
      gradle \
        :micronaut-pyronaut:prepareSdkMavenLocalEnvironment \
        :micronaut-pyronaut-dev:assemble \
        :micronaut-pyronaut-run:assemble \
        :micronaut-pyronaut-run-python:assemble \
        --no-daemon \
        --max-workers 4 \
        -PpyronautNativeImageCiArgs=${lib.escapeShellArg (lib.concatStringsSep " " ([ "-H:NativeLinkerOption=-L${pkgs.zlib.static}/lib" ] ++ nativeImageInitArgs))} \
        -Poverride.libs.managed-graal=${graalvm.version} \
        -Dmaven.repo.local="$TMPDIR/m2" \
        -Ppyronaut.sdk.mavenLocalRepository="$TMPDIR/m2"
      gradle \
        :micronaut-pyronaut:buildSdkWheel \
        --no-daemon \
        --max-workers 4 \
        --init-script ${wheelGradleInit} \
        -Poverride.libs.managed-graal=${graalvm.version} \
        -Dmaven.repo.local="$TMPDIR/m2"
      ${pyronautPython}/bin/python -m pip install --no-deps --prefix "$TMPDIR/sdk" pyronaut/build/wheel/dist/pyronaut-*.whl
      mkdir -p "$HOME/.pyronaut"
      cat > "$HOME/.pyronaut/settings.toml" <<EOF
      [native-images]
      base-url = "$PWD"
      version = "0.0.4-SNAPSHOT"
      EOF
      # The Java installer also keeps caches beneath user.home.
      export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Duser.home=$HOME"
      export PYTHONPATH="$TMPDIR/sdk/lib/python${pyronautPython.pythonVersion}/site-packages"
      "$TMPDIR/sdk/bin/pyronaut" setup \
        --local-repository "$TMPDIR/m2" \
        --progress off
      export PYRONAUT_LOCAL_REPOSITORY="$TMPDIR/m2"
      cp -r ${appSource} benchmark-app
      chmod -R u+w benchmark-app
      install -Dm644 ${tls}/server.p12 benchmark-app/config/server.p12
      "$TMPDIR/sdk/bin/pyronaut" install \
        --project-dir "$PWD/benchmark-app" \
        --local-repository "$TMPDIR/m2"
      "$TMPDIR/sdk/bin/pyronaut" build \
        --native \
        --project-dir "$PWD/benchmark-app" \
        -- \
        --no-sbom \
        -H:NativeLinkerOption=-L${pkgs.zlib.static}/lib ${lib.concatMapStringsSep " " lib.escapeShellArg baseNativeImageArgs}
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
    version = upstream.rev;
    src = upstream;

    nativeBuildInputs = [ pkgs.gnutar pkgs.makeWrapper pkgs.stdenv.cc ];
    dontStrip = config.benchmark.sut.runtimeInfo.keepDebugSymbols;

    buildPhase = ''
      runHook preBuild
      export HOME="$TMPDIR/home"
      export TMPDIR="$TMPDIR/tmp"
      export JAVA_HOME=${graalvm}
      export JAVA_TOOL_OPTIONS="-Duser.home=$HOME"
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
      ${configureThreading}
      cat ${poolConfiguration} >> benchmark-app/config/application.toml
      install -Dm644 ${tls}/server.p12 benchmark-app/config/server.p12
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
        # The launcher probes Truffle through its runtime JAR class loader.
        # Resolve that probe to the compiled class with runtime class loading disabled.
        cat > truffle-reflect-config.json <<'EOF'
        [{"name":"com.oracle.truffle.api.Truffle"}]
        EOF
        ${pyronautPython}/bin/python "$TMPDIR/pyronaut/sdk/bin/pyronaut" build \
          --native \
          --offline \
          --project-dir "$PWD/benchmark-app" \
          -- \
          --no-sbom \
          -H:ReflectionConfigurationFiles="$PWD/truffle-reflect-config.json" \
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
    type = lib.types.enum [ "event-loop" "io" ];
    default = "event-loop";
    description = "Controller threading: async handlers on the event loop, or synchronous handlers on cached platform IO threads.";
  };
  config = {
    benchmark = {
      sut.tlsHttp2 = true;
      jvm = {
        enable = isJvm;
        extraArgs = lib.optional isJvm "-Dpolyglot.engine.userResourceCache=/var/lib/sut/.cache/org.graalvm.polyglot";
      };
      sut = {
        package = sut;
        executable = "pyronaut";
        description = "Pyronaut ${if isJvm then "JVM" else "native"} benchmark server";
        environment = [ "XDG_CACHE_HOME=/var/lib/sut/.cache" ];
        metadata = {
          typePrefix = "pyronaut";
          parameters.threading = threading;
          parameters.contextPoolEnabled = lib.boolToString contextPool.enabled;
          parameters.contextPoolSize = toString contextPool.size;
          parameters.maxEventLoopContexts = toString contextPool.max-event-loop-contexts;
          parameters.sourceRevision = upstream.rev;
          parameters.graalvm = graalvm.version;
        };
      };
    };
    systemd.services.sut.serviceConfig = {
      TimeoutStartSec = lib.mkForce 600;
      WorkingDirectory = "/var/lib/sut";
    };
  };
}
