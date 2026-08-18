{ nixpkgs, lib, evaluatedSuites, evaluatedSuiteRuns, pgoStage }:
system:
let
  pkgs = import nixpkgs { inherit system; config.allowUnfree = true; };
  standard = evaluatedSuites.standard;
  standardRuns = evaluatedSuiteRuns.standard;
  enabledRuns = lib.filter (run: run.system.config.benchmark.sut.metadata.enabled) (lib.attrValues standardRuns);
  statusRequest = standard.config.benchmark.suite.statusRequest;
  trainingRequest = if standard.config.benchmark.suite.documents == [ ] then statusRequest else lib.head standard.config.benchmark.suite.documents;
  curlRequest = request:
    let
      headers = lib.mapAttrsToList (name: value: "--header ${lib.escapeShellArg "${name}: ${value}"}") request.requestHeaders;
      data = lib.optional (request.requestBody != null) "--data ${lib.escapeShellArg request.requestBody}";
    in "curl --fail --silent --show-error --request ${lib.escapeShellArg (if request.method == null then "GET" else request.method)} ${lib.concatStringsSep " " (headers ++ data)} ${lib.escapeShellArg "http://127.0.0.1:8080${request.uri}"}";
  assertResponse = request:
    if request.responseBody == null then "machine.succeed(${builtins.toJSON (curlRequest request)})"
    else "machine.succeed(${builtins.toJSON "response=$(${curlRequest request}); test \"$response\" = ${lib.escapeShellArg request.responseBody}"})";
  verifyEndpoints = ''
    machine.wait_for_open_port(8080, timeout=timedelta(seconds=120))
    machine.succeed(${builtins.toJSON "${curlRequest statusRequest} > /dev/null"})
    ${assertResponse trainingRequest}
  '';
  localRunModules = suite: runName: runModule: extraModules: [
    ./system/local-vm.nix
    ./system/run.nix
    { imports = suite.config.benchmark.suite.runModules; }
    { benchmark.run.name = runName; }
    {
      benchmark = {
        asyncProfiler.enable = lib.mkForce false;
        jvm.args = lib.mkForce [
          "-Xms1G"
          "-Xmx1G"
        ];
      };
    }
    runModule
  ] ++ extraModules;
  serviceTest = name: modules: pkgs.testers.runNixOSTest {
    inherit name;
    nodes.machine.imports = modules;
    testScript = ''
      from datetime import timedelta

      start_all()
      machine.wait_for_unit("multi-user.target")
      machine.succeed("systemctl start sut.service || (journalctl --no-pager -u sut.service; false)")
      machine.wait_for_unit("sut.service")
      ${verifyEndpoints}
    '';
  };
  runName = run: run.system.config.benchmark.run.name;
  smokeName = run: "${runName run}-smoke";
  runModulesFor = run: extraModules: localRunModules standard (runName run) run.runModule extraModules;
  collectorTest = collector: pkgs.testers.runNixOSTest {
    name = smokeName collector;
    nodes.machine.imports = runModulesFor collector [ (pgoStage "native-pgo-instrument" false null) ];
    testScript = ''
      from datetime import timedelta

      start_all()
      machine.wait_for_unit("multi-user.target")
      machine.succeed("systemd-run --unit=seed-sut-state --wait --collect --property=DynamicUser=yes --property=StateDirectory=sut ${pkgs.coreutils}/bin/true")
      machine.succeed("test -L /var/lib/sut")
      machine.succeed("/run/current-system/activate")
      machine.succeed("test ! -L /var/lib/sut")
      machine.succeed("systemctl start sut.service || (journalctl --no-pager -u sut.service; false)")
      machine.wait_for_unit("sut.service")
      ${verifyEndpoints}
      machine.succeed(${builtins.toJSON "for request in $(seq 1 64); do ${curlRequest trainingRequest} > /dev/null; done"})
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
    benchmark.sut.pgoDirectory = "${collectorOutput}";
  };
  pgoSmokeTests = collector:
    let
      collectorOutput = collectorTest collector;
      optimized = optimizedRun collector;
    in [
      (lib.nameValuePair (smokeName collector) collectorOutput)
      (lib.nameValuePair (smokeName optimized) (serviceTest (smokeName optimized) (runModulesFor optimized [ (pgoProfileModule collectorOutput) ])))
    ];
  serviceSmokeTests = map (run: lib.nameValuePair (smokeName run) (serviceTest (smokeName run) (runModulesFor run [ ])))
    (lib.filter (run: run.system.config.benchmark.sut.runtime != "native-pgo-instrument") enabledRuns);
in lib.listToAttrs (serviceSmokeTests ++ lib.concatMap pgoSmokeTests
  (lib.filter (run: run.system.config.benchmark.sut.runtime == "native-pgo-instrument") enabledRuns))
