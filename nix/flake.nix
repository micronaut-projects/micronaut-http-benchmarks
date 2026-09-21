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
    evalSuite = suiteName: suiteModule: lib.evalModules {
      specialArgs = { inherit suiteName; };
      modules = [ ./suites/module.nix ./hyperfoil-definitions.nix suiteModule ];
    };
    evaluatedSuites = lib.mapAttrs evalSuite suiteModules;

    optionalAttrs = attrs: lib.filterAttrs (_: value: value != null) attrs;
    requestMetadata = request: optionalAttrs {
      inherit (request) name method uri host requestType requestHeaders requestBody responseBody responseMatchingMode;
    };
    protocolMetadata = protocol: protocol;
    evaluateRun = suiteName: suite: runName: runModule: extraModules:
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
        inherit configurationName runModule runName suite suiteName system extraModules;
        trainingCase = null;
        metadata = {
          name = "${suiteName}-${cfg.run.name}";
          type = cfg.sut.metadata.type;
          parameters = cfg.sut.metadata.parameters;
          profiling = if cfg.sut.metadata.profiling == null then null else optionalAttrs cfg.sut.metadata.profiling;
        };
      };
    # The probe supplies logical run metadata only. Its native-PGO package is never
    # built: every deployable variant below receives its own training dependency.
    expandRun = suiteName: suite: runName: runModule:
      let
        probe = evaluateRun suiteName suite runName runModule [ ];
        isPgo = probe.system.config.benchmark.sut.runtime == "native-pgo";
        localBenchmark = import ./local-benchmark.nix {
          inherit lib;
          pkgs = import nixpkgs { system = "x86_64-linux"; config.allowUnfree = true; };
        };
        cases = lib.mapAttrs (protocolName: protocol: lib.listToAttrs (map (request:
          let
            variantName = "${runName}-${protocolName}-${request.name}";
            profile = localBenchmark.trainingProfile {
              inherit suite runName runModule request protocol;
              name = "${suiteName}-${variantName}";
            };
            variant = evaluateRun suiteName suite variantName runModule [
              { benchmark.sut.pgoProfile = profile; }
            ];
          in lib.nameValuePair request.name (if isPgo then variant // {
            trainingCase = { inherit request protocol; };
          } else probe)
        ) suite.config.benchmark.suite.documents)) suite.config.benchmark.suite.resolvedProtocols;
      in
      assert lib.assertMsg (probe.system.config.benchmark.sut.runtime != "native-pgo-instrument")
        "native-pgo-instrument is reserved for build-time training; select native-pgo for ${suiteName}-${runName}.";
      probe // {
        variants = if isPgo then lib.concatMap lib.attrValues (lib.attrValues cases) else [ probe ];
        metadata = probe.metadata // {
          nixosConfigurations = lib.mapAttrs (_: documents:
            lib.mapAttrs (_: run: run.configurationName) documents) cases;
        };
      };
    evaluatedSuiteRuns = lib.mapAttrs (suiteName: suite:
      lib.mapAttrs (runName: runModule: expandRun suiteName suite runName runModule)
        suite.config.benchmark.suite.runs
    ) evaluatedSuites;
    suiteRuns = lib.concatMap (run: run.variants)
      (lib.concatMap lib.attrValues (lib.attrValues evaluatedSuiteRuns));
    metadataSuites = lib.mapAttrs (suiteName: suite:
      let
        suiteConfig = suite.config.benchmark.suite;
      in {
        runs = map (run: run.metadata) (lib.filter (run: run.system.config.benchmark.sut.metadata.enabled) (lib.attrValues evaluatedSuiteRuns.${suiteName}));
        documents = map requestMetadata suiteConfig.documents;
        statusRequest = requestMetadata suiteConfig.statusRequest;
        protocols = lib.mapAttrs (_: protocolMetadata) suiteConfig.resolvedProtocols;
        benchmarkDefinitions = suite.config.benchmark.hyperfoil.definitions;
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
    relayAgent = system: (mkHost {
      inherit system;
      definition = ./system/relay-server.nix;
    }).config.system.build.relay-agent;
    maintenanceSut = system: name: runtime:
      (lib.nixosSystem {
        inherit system;
        modules = [ ./system/run.nix { imports = [ (../sut + "/${name}") ]; benchmark.sut.runtime = runtime; } ];
      }).config.benchmark.sut.package;
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
        profiledNativeRuns = lib.filter (run:
          run.system.config.benchmark.profiling.enable && run.system.config.benchmark.sut.runtimeInfo.isNative
        ) suiteRuns;
        profiledNonNativeRuns = lib.filter (run:
          run.system.config.benchmark.profiling.enable && !run.system.config.benchmark.sut.runtimeInfo.isNative
        ) suiteRuns;
        pySpyRuns = lib.filter (run:
          run.system.config.benchmark.profiling.enable
          && run.system.config.benchmark.sut.metadata.profiling.tool == "py-spy"
        ) suiteRuns;
      in
      assert lib.assertMsg (duplicateNames roleNames == [ ]) "Duplicate OCI instance metadata names: ${lib.concatStringsSep ", " (duplicateNames roleNames)}";
      assert lib.assertMsg (duplicateRunNames == [ ]) "Duplicate suite-local run names: ${lib.concatStringsSep ", " duplicateRunNames}";
      assert lib.assertMsg (duplicateNames configurationNames == [ ]) "Duplicate generated NixOS configuration names: ${lib.concatStringsSep ", " (duplicateNames configurationNames)}";
      assert lib.assertMsg (duplicateNames outputNames == [ ]) "Duplicate package output names: ${lib.concatStringsSep ", " (duplicateNames outputNames)}";
      assert lib.assertMsg (invalidRoles == [ ]) "Malformed OCI instance metadata for: ${lib.concatStringsSep ", " (map (role: role.name) invalidRoles)}";
      assert lib.assertMsg (builtins.all (suite: builtins.isList suite.runs && builtins.isList suite.documents && builtins.isAttrs suite.protocols && builtins.isAttrs suite.statusRequest) (lib.attrValues metadataSuites)) "Benchmark metadata has the suite schema expected by the load generator";
      assert lib.assertMsg (builtins.all (run: run.system.config.benchmark.sut.runtimeInfo.keepDebugSymbols && lib.all (argument: lib.elem argument run.system.config.benchmark.sut.runtimeInfo.nativeImageArgs) [ "-g" "-H:+PreserveFramePointer" "-H:-DeleteLocalSymbols" ]) profiledNativeRuns) "Profiled native runs must retain native-image debug symbols, frame pointers, and local symbols.";
      assert lib.assertMsg (builtins.all (run: run.metadata.profiling.injectedArtifact == "profile.jit.data" && run.metadata.profiling.symbolDirectory == "profile-symbols") profiledNativeRuns) "Profiled native runs must publish injected perf data and symbol-directory metadata.";
      assert lib.assertMsg (builtins.all (run: lib.attrNames run.metadata.profiling == [ "artifact" "tool" ]) profiledNonNativeRuns) "JFR and py-spy profiling metadata must retain its existing schema.";
      assert lib.assertMsg (builtins.all (run: run.system.config.systemd.services.sut.serviceConfig.KillSignal == "SIGINT") pySpyRuns) "Python py-spy profiling runs must stop with SIGINT.";
      true;

    instanceTypes = lib.listToAttrs (map (role: lib.nameValuePair role.name role.instance) rolesWithMetadata);
    benchmarkDefinitionsPackage = system:
      let
        pkgs = import nixpkgs { inherit system; };
        yaml = pkgs.formats.yaml { };
      in pkgs.linkFarm "benchmark-definitions" (lib.flatten (lib.mapAttrsToList (suiteName: suite:
        lib.flatten (lib.mapAttrsToList (protocolName: documents:
          lib.flatten (lib.mapAttrsToList (documentName: definitions: [
            {
              name = "${suiteName}/${protocolName}/${documentName}/normal.yaml";
              path = yaml.generate "${suiteName}-${protocolName}-${documentName}-normal.yaml" definitions.normal;
            }
          ]) documents)
        ) suite.benchmarkDefinitions)
      ) metadataSuites));
    benchmarkMetadata = { suites = lib.mapAttrs (_: suite: removeAttrs suite [ "benchmarkDefinitions" ]) metadataSuites; inherit instanceTypes; };
    metadataPackage = system: name: value: (import nixpkgs { inherit system; }).writeTextFile {
      inherit name;
      text = builtins.toJSON value;
    };
    localSmokeTests = import ./smoke-tests.nix {
      inherit nixpkgs lib evaluatedSuites evaluatedSuiteRuns expandRun;
    };
    rolePackage = role: lib.nameValuePair "${role.packageName or role.name}-system" (mkHost {
      system = role.instance.platform;
      definition = role.definition;
    }).config.system.build.toplevel;
    runPackage = run: lib.nameValuePair "${run.configurationName}-system" run.system.config.system.build.toplevel;
    packagesFor = system:
      let
        pkgs = import nixpkgs { inherit system; };
        activatableRoles = lib.filter (role: role.activatable && role.instance.platform == system) rolesWithMetadata;
        systemRuns = lib.filter (run: run.system.pkgs.system == system) suiteRuns;
      in
      assert metadataAssertions;
      {
        benchmark-tls = import ./tls.nix { inherit pkgs; };
        profiling-perf = pkgs.linuxPackages.perf;
        oci-bootstrap-image = ociBootstrapImage system;
        benchmark-metadata = metadataPackage system "benchmark-metadata.json" (benchmarkMetadata // { benchmarkDefinitions = benchmarkDefinitionsPackage system; });
        benchmark-definitions = benchmarkDefinitionsPackage system;
        relay-agent = relayAgent system;
        update-dependencies = pkgs.writeShellApplication {
          name = "update-dependencies";
          inheritPath = false;
          runtimeInputs = [ pkgs.nix pkgs.gitMinimal pkgs.ripgrep pkgs.perl pkgs.gawk pkgs.coreutils ];
          text = builtins.readFile ./update-dependencies;
        };
        update-micronaut-framework = maintenanceSut system "micronaut-framework" "native";
        update-pure-netty = maintenanceSut system "pure-netty" "hotspot";
        update-quarkus = maintenanceSut system "quarkus" "native";
        update-vertx = maintenanceSut system "vertx" "hotspot";
        update-helidon-nima = maintenanceSut system "helidon-nima" "hotspot";
        update-spring-boot = maintenanceSut system "spring-boot" "hotspot";
        update-pyronaut = (maintenanceSut system "pyronaut" "hotspot").pyronaut;
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
