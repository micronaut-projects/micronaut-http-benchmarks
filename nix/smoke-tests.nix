{ nixpkgs, lib, evaluatedSuites, evaluatedSuiteRuns, pgoStage }:
system:
let
  pkgs = import nixpkgs { inherit system; config.allowUnfree = true; };
  standard = evaluatedSuites.standard;
  standardRuns = evaluatedSuiteRuns.standard;
  enabledRuns = lib.filter (run: run.system.config.benchmark.sut.metadata.enabled) (lib.attrValues standardRuns);
  statusRequest = standard.config.benchmark.suite.statusRequest;
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
  assertResponse = baseUrl: extraArgs: request:
    if request.responseBody == null then "machine.succeed(${builtins.toJSON (curlRequest baseUrl extraArgs request)})"
    else "machine.succeed(${builtins.toJSON "response=$(${curlRequest baseUrl extraArgs request}); test \"$response\" = ${lib.escapeShellArg request.responseBody}"})";
  verifyEndpoints = tlsHttp2: ''
    machine.succeed(${builtins.toJSON "${curlRequest "http://127.0.0.1:8080" "" statusRequest} > /dev/null"})
    ${assertResponse "http://127.0.0.1:8080" "" trainingRequest}
  '' + lib.optionalString tlsHttp2 ''
    machine.succeed(${builtins.toJSON "test \"$(${curlRequest "https://127.0.0.1:8443" "--http2 --insecure --output /dev/null --write-out '%{http_version}'" statusRequest})\" = 2"})
    ${assertResponse "https://127.0.0.1:8443" "--http2 --insecure" trainingRequest}
  '';
  localRunModules = suite: runName: profilerEnabled: runModule: extraModules: [
    ./system/local-vm.nix
    ./system/run.nix
    { imports = suite.config.benchmark.suite.runModules; }
    { benchmark.run.name = runName; }
    {
      benchmark = {
        asyncProfiler.enable = lib.mkForce profilerEnabled;
        jvm.args = lib.mkForce [
          "-Xms1G"
          "-Xmx1G"
        ];
      };
    }
    runModule
  ] ++ extraModules;
  serviceTest = name: tlsHttp2: asyncProfiler: modules: pkgs.testers.runNixOSTest {
    inherit name;
    nodes.machine.imports = modules;
    testScript = ''
      start_all()
      machine.wait_for_unit("multi-user.target")
      machine.succeed("systemctl start sut.service || (journalctl --no-pager -u sut.service; false)")
      machine.wait_for_unit("sut.service")
      ${verifyEndpoints tlsHttp2}
      ${lib.optionalString asyncProfiler ''
        machine.succeed("systemctl stop sut.service")
        machine.succeed("test -s /var/lib/sut/profile.jfr")
      ''}
    '';
  };
  runName = run: run.system.config.benchmark.run.name;
  smokeName = run: "${runName run}-smoke";
  runModulesFor = run: profilerEnabled: extraModules: localRunModules standard (runName run) profilerEnabled run.runModule extraModules;
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
      (lib.nameValuePair (smokeName optimized) (serviceTest (smokeName optimized) optimized.system.config.benchmark.sut.tlsHttp2 false (runModulesFor optimized false [ (pgoProfileModule collectorOutput) ])))
    ];
  serviceSmokeTests = map (run: lib.nameValuePair (smokeName run) (serviceTest (smokeName run) run.system.config.benchmark.sut.tlsHttp2 false (runModulesFor run false [ ])))
    (lib.filter (run: run.system.config.benchmark.sut.runtime != "native-pgo-instrument") enabledRuns);
  pyronaut = standardRuns.pyronaut;
  pyronautAsyncProfilerSmoke = lib.nameValuePair "pyronaut-async-profiler-smoke" (serviceTest "pyronaut-async-profiler-smoke" pyronaut.system.config.benchmark.sut.tlsHttp2 true (runModulesFor pyronaut true [ ]));
in lib.listToAttrs ([ pyronautAsyncProfilerSmoke ] ++ serviceSmokeTests ++ lib.concatMap pgoSmokeTests
  (lib.filter (run: run.system.config.benchmark.sut.runtime == "native-pgo-instrument") enabledRuns))
