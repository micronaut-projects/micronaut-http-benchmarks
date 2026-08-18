{
  description = "Micronaut Framework benchmark infra";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    disko.url = "github:nix-community/disko";
    disko.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { nixpkgs, disko, ... }:
  let
    lib = nixpkgs.lib;
    supportedSystems = [ "x86_64-linux" "aarch64-linux" ];
    suiteModules = import ./suites;
    runModules = [
      ./system/benchmark-bootstrap.nix
      ./system/run.nix
    ];
    evalSuite = suiteModule: lib.evalModules {
      modules = [ ./suites/module.nix suiteModule ];
    };
    evaluatedSuites = lib.mapAttrs (_: evalSuite) suiteModules;

    optionalAttrs = attrs: lib.filterAttrs (_: value: value != null) attrs;
    requestMetadata = request: optionalAttrs {
      inherit (request) name method uri host requestType requestHeaders requestBody responseBody responseMatchingMode;
    };
    protocolMetadata = protocol: protocol;

    evaluateRun = suiteName: suite: runName: runModule: extraModules: metadataOverrides:
      let
        configurationName = "${suiteName}-${runName}";
        system = lib.nixosSystem {
          system = "x86_64-linux";
          modules = [ disko.nixosModules.disko ] ++ runModules ++ [
            { imports = suite.config.benchmark.suite.runModules; }
            { benchmark.run.name = runName; }
            runModule
          ] ++ extraModules;
        };
        cfg = system.config.benchmark;
      in {
        inherit configurationName runModule system;
        metadata = {
          name = "${suiteName}-${cfg.run.name}";
          type = cfg.sut.metadata.type;
          parameters = cfg.sut.metadata.parameters;
          nixosConfiguration = configurationName;
          asyncProfiler = cfg.asyncProfiler.enable;
          pgo = cfg.sut.metadata.pgo;
        } // metadataOverrides;
      };
    pgoStage = runtime: enabled: pgo: {
      benchmark.sut = {
        runtime = lib.mkForce runtime;
        metadata.enabled = lib.mkForce enabled;
        metadata.pgo = lib.mkForce pgo;
      };
    };
    expandRun = suiteName: suite: runName: runModule:
      let
        probe = evaluateRun suiteName suite runName runModule [ ] { };
        collectorConfiguration = "${suiteName}-${runName}-collector";
        optimizedConfiguration = "${suiteName}-${runName}-optimized";
        pgo = {
          inherit optimizedConfiguration;
        };
      in if probe.system.config.benchmark.sut.runtime == "native-pgo" then {
        "${runName}-collector" = evaluateRun suiteName suite "${runName}-collector" runModule [ (pgoStage "native-pgo-instrument" true pgo) ] {
          name = "${suiteName}-${runName}";
        };
        "${runName}-optimized" = evaluateRun suiteName suite "${runName}-optimized" runModule [ (pgoStage "native-pgo" false null) ] { };
      } else {
        "${runName}" = probe;
      };
    evaluatedSuiteRuns = lib.mapAttrs (suiteName: suite:
      lib.foldl' (runs: runName: runs // expandRun suiteName suite runName suite.config.benchmark.suite.runs.${runName}) { }
        (lib.attrNames suite.config.benchmark.suite.runs)
    ) evaluatedSuites;
    suiteRuns = lib.concatMap lib.attrValues (lib.attrValues evaluatedSuiteRuns);
    metadataSuites = lib.mapAttrs (suiteName: suite:
      let
        suiteConfig = suite.config.benchmark.suite;
      in {
        runs = map (run: run.metadata) (lib.filter (run: run.system.config.benchmark.sut.metadata.enabled) (lib.attrValues evaluatedSuiteRuns.${suiteName}));
        documents = map requestMetadata suiteConfig.documents;
        statusRequest = requestMetadata suiteConfig.statusRequest;
        protocols = lib.mapAttrs (_: protocolMetadata) suiteConfig.resolvedProtocols;
      }
    ) evaluatedSuites;

    roles = [
      { name = "benchmark-server"; packageName = "benchmark-bootstrap"; definition = ./system/benchmark-bootstrap.nix; activatable = true; }
      { name = "relay-server"; definition = ./system/relay-server.nix; activatable = true; }
      { name = "hyperfoil-agent"; definition = ./system/hyperfoil-agent.nix; activatable = true; }
      { name = "hyperfoil-controller"; definition = ./system/hyperfoil-controller.nix; activatable = true; }
      { name = "nginx"; definition = ./system/nginx.nix; activatable = false; }
      { name = "postgresql"; definition = ./system/postgresql.nix; activatable = false; }
    ];
    mkHost = { system, definition }: lib.nixosSystem {
      inherit system;
      modules = [ disko.nixosModules.disko definition ];
    };
    mkOciBootstrapImage = system: lib.nixosSystem {
      inherit system;
      modules = [ ./system/oci-bootstrap.nix ];
    };
    ociBootstrapImage = system: (mkOciBootstrapImage system).config.system.build.image;
    roleMetadata = role: (mkHost {
      system = "x86_64-linux";
      definition = role.definition;
    }).config.benchmark.oci.instance;
    rolesWithMetadata = map (role: role // { instance = roleMetadata role; }) roles;
    duplicateNames = names: lib.unique (lib.filter (name: lib.count (candidate: candidate == name) names > 1) names);

    metadataAssertions =
      let
        roleNames = map (role: role.name) rolesWithMetadata;
        duplicateRunNames = lib.concatMap (runs: duplicateNames (map (run: run.metadata.name) (lib.attrValues runs))) (lib.attrValues evaluatedSuiteRuns);
        configurationNames = map (run: run.configurationName) suiteRuns;
        roleOutputNames = map (role: "${role.packageName or role.name}-system") (lib.filter (role: role.activatable) rolesWithMetadata);
        outputNames = roleOutputNames ++ map (run: "${run.configurationName}-system") suiteRuns;
        invalidRoles = lib.filter (role:
          role.instance.shape == ""
          || role.instance.ocpus <= 0
          || role.instance.memoryInGb <= 0
          || (role.instance.diskPerformanceUnits != null && role.instance.diskPerformanceUnits < 0)
          || (role.activatable && role.instance.platform == null)
        ) rolesWithMetadata;
      in
      assert lib.assertMsg (duplicateNames roleNames == [ ]) "Duplicate OCI instance metadata names: ${lib.concatStringsSep ", " (duplicateNames roleNames)}";
      assert lib.assertMsg (duplicateRunNames == [ ]) "Duplicate suite-local run names: ${lib.concatStringsSep ", " duplicateRunNames}";
      assert lib.assertMsg (duplicateNames configurationNames == [ ]) "Duplicate generated NixOS configuration names: ${lib.concatStringsSep ", " (duplicateNames configurationNames)}";
      assert lib.assertMsg (duplicateNames outputNames == [ ]) "Duplicate package output names: ${lib.concatStringsSep ", " (duplicateNames outputNames)}";
      assert lib.assertMsg (invalidRoles == [ ]) "Malformed OCI instance metadata for: ${lib.concatStringsSep ", " (map (role: role.name) invalidRoles)}";
      assert lib.assertMsg (builtins.all (suite: builtins.isList suite.runs && builtins.isList suite.documents && builtins.isAttrs suite.protocols && builtins.isAttrs suite.statusRequest) (lib.attrValues metadataSuites)) "Benchmark metadata has the suite schema expected by the load generator";
      true;

    instanceTypes = lib.listToAttrs (map (role: lib.nameValuePair role.name role.instance) rolesWithMetadata);
    benchmarkMetadata = { suites = metadataSuites; inherit instanceTypes; };
    metadataPackage = system: name: value: (import nixpkgs { inherit system; }).writeTextFile {
      inherit name;
      text = builtins.toJSON value;
    };
    localSmokeTests = import ./smoke-tests.nix {
      inherit nixpkgs lib evaluatedSuites evaluatedSuiteRuns pgoStage;
    };
    rolePackage = role: lib.nameValuePair "${role.packageName or role.name}-system" (mkHost {
      system = role.instance.platform;
      definition = role.definition;
    }).config.system.build.toplevel;
    runPackage = run: lib.nameValuePair "${run.configurationName}-system" run.system.config.system.build.toplevel;
    packagesFor = system:
      let
        activatableRoles = lib.filter (role: role.activatable && role.instance.platform == system) rolesWithMetadata;
        systemRuns = lib.filter (run: run.system.pkgs.system == system) suiteRuns;
      in
      assert metadataAssertions;
      {
        oci-bootstrap-image = ociBootstrapImage system;
        benchmark-metadata = metadataPackage system "benchmark-metadata.json" benchmarkMetadata;
      }
      // lib.listToAttrs (map rolePackage activatableRoles)
      // lib.listToAttrs (map runPackage systemRuns);
  in {
    packages = lib.genAttrs supportedSystems packagesFor;
    checks = lib.genAttrs supportedSystems (system: {
      benchmark-suite-shape = metadataPackage system "benchmark-suite-shape.json" benchmarkMetadata;
    } // lib.optionalAttrs (system == "x86_64-linux") (localSmokeTests system));
  };
}
