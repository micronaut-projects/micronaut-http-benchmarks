{ nixpkgs, lib, evaluatedSuites, evaluatedSuiteRuns, pgoStage }:
system:
let
  pkgs = import nixpkgs { inherit system; config.allowUnfree = true; };
  standard = evaluatedSuites.standard;
  standardRuns = evaluatedSuiteRuns.standard;
  enabledRuns = lib.filter (run: run.system.config.benchmark.sut.metadata.enabled) (lib.attrValues standardRuns);
  statusRequest = standard.config.benchmark.suite.statusRequest;
  hyperfoil = import ./system/hyperfoil.nix { inherit pkgs; };
  localTarget = {
    httpUrl = "http://127.0.0.1";
    httpAuthority = "127.0.0.1";
    httpPort = 8080;
    httpsUrl = "https://localhost";
    httpsAuthority = "localhost";
    httpsPort = 8443;
  };
  localProtocol = protocol: (removeAttrs protocol [ "enable" ]) // {
    sharedConnections = 1;
    pipeliningLimit = 1;
    maxHttp2Streams = 1;
    compileOps = 1;
    ops = [ 1 ];
    sla = { "0.99" = "20s"; };
  };
  localRequests = lib.evalModules {
    modules = [
      ./hyperfoil-definitions.nix
      {
        benchmark.hyperfoil.requests = {
          status-http1 = {
            mode = "local";
            target = localTarget;
            request = statusRequest;
            protocol = localProtocol (protocolFor "http1");
          };
          training-http1 = {
            mode = "local";
            target = localTarget;
            request = trainingRequest;
            protocol = localProtocol (protocolFor "http1");
          };
          status-https2 = {
            mode = "local";
            target = localTarget;
            request = statusRequest;
            protocol = localProtocol (protocolFor "https2");
          };
          training-https2 = {
            mode = "local";
            target = localTarget;
            request = trainingRequest;
            protocol = localProtocol (protocolFor "https2");
          };
        };
      }
    ];
  };
  localDefinition = name:
    (pkgs.formats.yaml { }).generate "local-${name}.yaml"
      localRequests.config.benchmark.hyperfoil.rendered.${name};
  runDefinition = definition:
    "JAVA_OPTS='-Dio.hyperfoil.jitter.watchdog.threshold=86400000 -Dio.hyperfoil.cpu.watchdog.idle.threshold=0' ${hyperfoil}/bin/run.sh ${definition} --fail-on-errors --export /tmp/hyperfoil-result.json --export-format JSON";
  trainingRequest = if standard.config.benchmark.suite.documents == [ ] then statusRequest else lib.head standard.config.benchmark.suite.documents;
  curlRequest = baseUrl: extraArgs: request:
    let
      requestHeaders = request.requestHeaders // {
        host = if request.host == null then "example.com" else request.host;
        content-type = if request.requestType == null then "application/json" else request.requestType;
      };
      headers = lib.mapAttrsToList (name: value: "--header ${lib.escapeShellArg "${name}: ${value}"}") requestHeaders;
      data = lib.optional (request.requestBody != null) "--data ${lib.escapeShellArg request.requestBody}";
    in "curl --fail --silent --show-error --max-time 20 ${extraArgs} --request ${lib.escapeShellArg (if request.method == null then "GET" else request.method)} --request-target ${lib.escapeShellArg request.uri} ${lib.concatStringsSep " " (headers ++ data)} ${lib.escapeShellArg baseUrl}";
  protocolFor = name: standard.config.benchmark.suite.protocols.${name} // {
    protocol = if name == "http1" then "HTTP1" else if name == "https1" then "HTTPS1" else "HTTPS2";
  };
  verifyEndpoints = tlsHttp2: ''
    machine.succeed(${builtins.toJSON (runDefinition (localDefinition "status-http1"))})
    machine.succeed(${builtins.toJSON (runDefinition (localDefinition "training-http1"))})
  '' + lib.optionalString tlsHttp2 ''
    machine.succeed(${builtins.toJSON (runDefinition (localDefinition "status-https2"))})
    machine.succeed(${builtins.toJSON (runDefinition (localDefinition "training-https2"))})
  '';
  verifyNativeProfile = sutPackage: ''
    machine.succeed("test -s /var/lib/sut/profile.jit.data")
    machine.succeed("test -d /var/lib/sut/profile-symbols")
    machine.succeed("test -s /var/lib/sut/profile-symbols/proc/kallsyms")
    machine.succeed("${pkgs.gnugrep}/bin/grep -Eq '^[0-9a-fA-F]*[1-9a-fA-F][0-9a-fA-F]* [tT] ' /var/lib/sut/profile-symbols/proc/kallsyms")
    machine.succeed("runuser -u sut -- ${pkgs.linuxPackages.perf}/bin/perf script --ns --symfs /var/lib/sut/profile-symbols --kallsyms /var/lib/sut/profile-symbols/proc/kallsyms -i /var/lib/sut/profile.jit.data > /tmp/profile.txt")
    machine.succeed("test -s /tmp/profile.txt")
    machine.succeed("${pkgs.gnugrep}/bin/grep -Fq '([kernel.kallsyms])' /tmp/profile.txt")
    machine.succeed(${builtins.toJSON ''
      elf_found=false
      while IFS= read -r -d "" source; do
        if ${pkgs.binutils}/bin/readelf -h "$source" > /dev/null 2>&1; then
          elf_found=true
          staged="/var/lib/sut/profile-symbols$source"
          test -f "$staged"
          ${pkgs.diffutils}/bin/cmp "$source" "$staged"
        fi
      done < <(${pkgs.findutils}/bin/find -L ${lib.escapeShellArg (toString sutPackage)} -type f -perm /111 -print0)
      test "$elf_found" = true

      while IFS= read -r -d "" source; do
        staged="/var/lib/sut/profile-symbols$source"
        test -f "$staged"
        ${pkgs.diffutils}/bin/cmp "$source" "$staged"
      done < <(${pkgs.findutils}/bin/find /var/lib/sut/jitdump -type f -print0)
    ''})
  '';
  localRunModules = suite: runName: profilingEnabled: runModule: extraModules: [
    ./system/local-vm.nix
    ./system/run.nix
    { imports = suite.config.benchmark.suite.runModules; }
    { benchmark.run.name = runName; }
    {
      benchmark = {
        profiling.enable = lib.mkForce profilingEnabled;
        jvm.args = lib.mkForce [
          "-Xms1G"
          "-Xmx1G"
        ];
      };
      environment.systemPackages = [ hyperfoil pkgs.jdk25_headless ];
    }
    runModule
  ] ++ extraModules;
  serviceTest = { name, tlsHttp2, profiling, pySpy, artifact, nativeProfile ? null, modules }: pkgs.testers.runNixOSTest {
    inherit name;
    nodes.machine.imports = modules;
    nodes.machine.boot.kernel.sysctl = lib.mkIf (nativeProfile != null) {
      "kernel.perf_event_paranoid" = 1;
      "kernel.kptr_restrict" = 0;
    };
    testScript = ''
      start_all()
      machine.wait_for_unit("multi-user.target")
      ${lib.optionalString (nativeProfile != null) ''
        machine.succeed("runuser -u sut -- ${pkgs.runtimeShell} -c 'mkdir -p /var/lib/sut/profile-symbols /var/lib/sut/jitdump; printf stale > /var/lib/sut/profile.data; printf stale > /var/lib/sut/profile.jit.data; printf stale > /var/lib/sut/profile-symbols/stale; printf stale > /var/lib/sut/jitdump/stale'")
      ''}
      machine.succeed("systemctl start sut.service || (journalctl --no-pager -u sut.service; false)")
      machine.wait_for_unit("sut.service")
      ${lib.optionalString pySpy ''
        machine.succeed("systemctl cat sut.service | ${pkgs.gnugrep}/bin/grep -Fx KillSignal=SIGINT")
      ''}
      ${verifyEndpoints tlsHttp2}
      ${lib.optionalString profiling ''
        machine.succeed("systemctl stop sut.service")
        machine.succeed("test -s /var/lib/sut/${artifact}")
        ${lib.optionalString (nativeProfile != null) ''
          machine.succeed("test ! -e /var/lib/sut/jitdump/stale")
          machine.succeed("test ! -e /var/lib/sut/profile-symbols/stale")
        ''}
        ${lib.optionalString (nativeProfile != null) (verifyNativeProfile nativeProfile)}
      ''}
    '';
  };
  runName = run: run.system.config.benchmark.run.name;
  smokeName = run: "${runName run}-smoke";
  runModulesFor = run: profilingEnabled: extraModules: localRunModules standard (runName run) profilingEnabled run.runModule extraModules;
  collectorTest = collector: pkgs.testers.runNixOSTest {
    name = smokeName collector;
    nodes.machine.imports = runModulesFor collector false [ (pgoStage "native-pgo-instrument" false null) ];
    testScript = ''
      start_all()
      machine.wait_for_unit("multi-user.target")
      machine.succeed("systemd-run --unit=seed-sut-state --wait --collect --property=DynamicUser=yes --property=StateDirectory=sut ${pkgs.coreutils}/bin/true")
      machine.succeed("test -L /var/lib/sut")
      machine.succeed("/run/current-system/activate")
      machine.succeed("test ! -L /var/lib/sut")
      machine.succeed("systemctl start sut.service || (journalctl --no-pager -u sut.service; false)")
      machine.wait_for_unit("sut.service")
      ${verifyEndpoints collector.system.config.benchmark.sut.tlsHttp2}
      machine.succeed(${builtins.toJSON "for request in $(seq 1 64); do ${curlRequest "http://127.0.0.1:8080" "" trainingRequest} > /dev/null; done"})
      machine.succeed("systemctl stop sut.service")
      machine.wait_until_succeeds("test -s /var/lib/sut/pgo/default.iprof")
      machine.succeed("runuser -u nixbld1 -- test -r /var/lib/sut/pgo/default.iprof")
      machine.copy_from_machine("/var/lib/sut/pgo/default.iprof")
    '';
  };
  optimizedRun = collector:
    lib.findFirst (run: run.configurationName == collector.system.config.benchmark.sut.metadata.pgo.optimizedConfiguration)
      (throw "Missing optimized PGO run for ${runName collector}") (lib.attrValues standardRuns);
  pgoProfileModule = collectorOutput: {
    benchmark.sut.pgoBuildDirectory = collectorOutput;
  };
  pgoSmokeTests = collector:
    let
      collectorOutput = collectorTest collector;
      optimized = optimizedRun collector;
    in [
      (lib.nameValuePair (smokeName collector) collectorOutput)
      (lib.nameValuePair (smokeName optimized) (serviceTest {
        name = smokeName optimized;
        tlsHttp2 = optimized.system.config.benchmark.sut.tlsHttp2;
        profiling = false;
        pySpy = false;
        artifact = "";
        modules = runModulesFor optimized false [ (pgoProfileModule collectorOutput) ];
      }))
     ];
  serviceSmokeTests = map (run: lib.nameValuePair (smokeName run) (serviceTest {
    name = smokeName run;
    tlsHttp2 = run.system.config.benchmark.sut.tlsHttp2;
    profiling = false;
    pySpy = false;
    artifact = "";
    modules = runModulesFor run false [ ];
  }))
    (lib.filter (run: run.system.config.benchmark.sut.runtime != "native-pgo-instrument") enabledRuns);
  pyronaut = standardRuns.pyronaut;
  profilingSmoke = run: lib.nameValuePair "${runName run}-profiling-smoke" (serviceTest {
    name = "${runName run}-profiling-smoke";
    tlsHttp2 = run.system.config.benchmark.sut.tlsHttp2;
    profiling = true;
    pySpy = run.system.config.benchmark.sut.metadata.profiling.tool == "py-spy";
    artifact = run.system.config.benchmark.sut.metadata.profiling.artifact;
    nativeProfile = if run.system.config.benchmark.sut.runtimeInfo.isNative then run.system.config.benchmark.sut.package else null;
    modules = runModulesFor run true [ ];
  });
in lib.listToAttrs ((map profilingSmoke (lib.filter (run: run.system.config.benchmark.sut.runtime != "native-pgo-instrument") enabledRuns)) ++ serviceSmokeTests ++ lib.concatMap pgoSmokeTests
  (lib.filter (run: run.system.config.benchmark.sut.runtime == "native-pgo-instrument") enabledRuns))
