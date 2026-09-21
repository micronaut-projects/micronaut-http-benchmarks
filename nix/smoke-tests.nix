{ nixpkgs, lib, evaluatedSuites, evaluatedSuiteRuns, expandRun }:
system:
let
  pkgs = import nixpkgs { inherit system; config.allowUnfree = true; };
  standard = evaluatedSuites.standard;
  standardRuns = evaluatedSuiteRuns.standard;
  # Exercise opt-in native builds even when they are absent from the benchmark matrix.
  checkRuns = lib.concatMap (framework: map (runtime:
    expandRun "standard" standard "${framework}-${if runtime == "native-pgo" then "pgo" else runtime}" {
      imports = [ (../sut + (if framework == "micronaut" then "/micronaut-framework" else "/quarkus")) ];
      benchmark.sut.runtime = runtime;
    }
  ) [ "native" "native-pgo" ]) [ "micronaut" "quarkus" ];
  enabledRuns = lib.concatMap (run: run.variants)
    ((lib.filter (run: run.system.config.benchmark.sut.metadata.enabled) (lib.attrValues standardRuns))
      ++ lib.filter (run: !(builtins.hasAttr run.runName standardRuns)) checkRuns);
  statusRequest = standard.config.benchmark.suite.statusRequest;
  localBenchmark = import ./local-benchmark.nix { inherit pkgs lib; };
  inherit (localBenchmark) runDefinition;
  trainingRequest = if standard.config.benchmark.suite.documents == [ ] then statusRequest else lib.head standard.config.benchmark.suite.documents;
  localDefinition = request: protocolName: localBenchmark.definition {
    inherit request;
    protocol = protocolFor protocolName;
  };
  protocolFor = name: standard.config.benchmark.suite.protocols.${name} // {
    protocol = if name == "http1" then "HTTP1" else if name == "https1" then "HTTPS1" else "HTTPS2";
  };
  verifyEndpoints = tlsHttp2: ''
    machine.succeed(${builtins.toJSON (runDefinition (localDefinition statusRequest "http1"))})
    machine.succeed(${builtins.toJSON (runDefinition (localDefinition trainingRequest "http1"))})
  '' + lib.optionalString tlsHttp2 ''
    machine.succeed(${builtins.toJSON (runDefinition (localDefinition statusRequest "https2"))})
    machine.succeed(${builtins.toJSON (runDefinition (localDefinition trainingRequest "https2"))})
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
  serviceTest = { name, tlsHttp2, profiling, pySpy, artifact, nativeProfile ? null, trainingCase ? null, modules }: pkgs.testers.runNixOSTest {
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
      ${if trainingCase == null then verifyEndpoints tlsHttp2 else ''
        machine.succeed(${builtins.toJSON (runDefinition (localBenchmark.definition {
          inherit (trainingCase) protocol;
          request = statusRequest;
        }))})
        machine.succeed(${builtins.toJSON (runDefinition (localBenchmark.definition trainingCase))})
      ''}
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
  runModulesFor = run: profilingEnabled: localBenchmark.runModules run.suite (runName run) profilingEnabled run.runModule
    (run.extraModules ++ lib.optional (run.trainingCase != null) {
      # Both checks exercise the deployable optimized binary, including its debug symbols.
      benchmark.sut.package = lib.mkForce run.system.config.benchmark.sut.package;
    });
  serviceSmokeTests = map (run: lib.nameValuePair (smokeName run) (serviceTest {
    name = smokeName run;
    tlsHttp2 = run.system.config.benchmark.sut.tlsHttp2;
    profiling = false;
    pySpy = false;
    artifact = "";
    inherit (run) trainingCase;
    modules = runModulesFor run false;
  }))
    enabledRuns;
  profilingSmoke = run: lib.nameValuePair "${runName run}-profiling-smoke" (serviceTest {
    name = "${runName run}-profiling-smoke";
    tlsHttp2 = run.system.config.benchmark.sut.tlsHttp2;
    profiling = true;
    pySpy = run.system.config.benchmark.sut.metadata.profiling.tool == "py-spy";
    artifact = run.system.config.benchmark.sut.metadata.profiling.artifact;
    nativeProfile = if run.system.config.benchmark.sut.runtimeInfo.isNative then run.system.config.benchmark.sut.package else null;
    inherit (run) trainingCase;
    modules = runModulesFor run true;
  });
in lib.listToAttrs ((map profilingSmoke enabledRuns) ++ serviceSmokeTests)
