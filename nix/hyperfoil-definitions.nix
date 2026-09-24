{ config, lib, suiteName, ... }:
let
  mkOption = lib.mkOption;
  types = lib.types;
  benchmarkTypes = import ./suites/types.nix { lib = lib; };
  render = import ./render-hyperfoil.nix { inherit lib; };
  statusArtifactName = "_status";
  validArtifactComponent = name: builtins.match "^[a-zA-Z0-9][a-zA-Z0-9._-]*$" name != null;
  benchmarkTarget = {
    httpUrl = "http://10.0.0.2"; httpAuthority = "10.0.0.2"; httpPort = 8080;
    httpsUrl = "https://10.0.0.2"; httpsAuthority = "10.0.0.2"; httpsPort = 8443;
  };
  suite = config.benchmark.suite;
  duplicateNames = names: lib.unique (lib.filter (name: lib.count (candidate: candidate == name) names > 1) names);
  duplicateDocumentNames = duplicateNames (map (request: request.name) suite.documents);
  definitions =
    assert lib.assertMsg (validArtifactComponent suiteName) "Unsafe suite name in Hyperfoil artifact path: ${suiteName}";
    assert lib.assertMsg (duplicateDocumentNames == [ ]) "Duplicate suite document names: ${lib.concatStringsSep ", " duplicateDocumentNames}";
    assert lib.assertMsg (suite.statusRequest.name != statusArtifactName) "Reserved status request name: ${statusArtifactName}";
    assert lib.assertMsg (validArtifactComponent suite.statusRequest.name) "Unsafe status request name in Hyperfoil artifact path: ${suite.statusRequest.name}";
    assert lib.assertMsg (!(builtins.any (request: request.name == statusArtifactName) suite.documents)) "Reserved document name: ${statusArtifactName}";
    lib.mapAttrs (protocolName: protocol: lib.listToAttrs (map (request:
      assert lib.assertMsg (validArtifactComponent protocolName) "Unsafe protocol name in Hyperfoil artifact path: ${protocolName}";
      assert lib.assertMsg (validArtifactComponent request.name) "Unsafe request name in Hyperfoil artifact path: ${request.name}";
      lib.nameValuePair request.name (render {
        mode = "normal";
        inherit protocol request;
        target = benchmarkTarget;
        settings = {
          inherit (config.benchmark.hyperfoil) warmupDuration warmupUsers benchmarkDuration sessionLimitFactor;
        };
      })) suite.documents)) suite.resolvedProtocols;
in {
  options.benchmark.hyperfoil = {
    warmupDuration = mkOption {
      type = benchmarkTypes.duration;
      default = "1m";
      description = "Duration of the Hyperfoil warmup phase.";
    };
    warmupUsers = mkOption {
      type = types.addCheck types.int (value: value > 0);
      default = 200;
      description = "Total concurrent clients across all agents during benchmark warmup.";
    };
    benchmarkDuration = mkOption {
      type = benchmarkTypes.duration;
      default = "2m";
      description = "Duration of each main Hyperfoil benchmark phase.";
    };
    pgoDuration = mkOption {
      type = benchmarkTypes.duration;
      default = "2m";
      description = "Duration of build-time Hyperfoil PGO training.";
    };
    sessionLimitFactor = mkOption {
      type = types.addCheck types.number (value: value > 0);
      default = 2;
      description = "Multiplier used to derive Hyperfoil session limits from request rates.";
    };
    definitions = mkOption {
      type = types.attrs;
      readOnly = true;
      description = "Generated normal Hyperfoil benchmark definitions for this suite.";
    };
  };

  config.benchmark.hyperfoil.definitions = definitions;
}
