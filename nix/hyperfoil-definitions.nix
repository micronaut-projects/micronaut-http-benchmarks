{ config, lib, ... }@moduleArgs:
let
  mkOption = lib.mkOption;
  types = lib.types;
  benchmarkTypes = import ./suites/types.nix { lib = lib; };
  suiteName = moduleArgs.suiteName or null;
  statusArtifactName = "_status";
  validArtifactComponent = name: builtins.match "^[a-zA-Z0-9][a-zA-Z0-9._-]*$" name != null;
  port = types.addCheck types.int (value: value > 0 && value <= 65535);
  optionalAttrs = attrs: lib.filterAttrs (_: value: value != null) attrs;
  targetRecord = types.submodule {
    options = {
      httpUrl = mkOption { type = types.str; description = "HTTP base URL used by HTTP/1 requests."; };
      httpAuthority = mkOption { type = types.str; description = "HTTP authority host sent by HTTP/1 requests."; };
      httpPort = mkOption { type = port; description = "HTTP port used by HTTP/1 requests."; };
      httpsUrl = mkOption { type = types.str; description = "HTTPS base URL used by HTTPS requests."; };
      httpsAuthority = mkOption { type = types.str; description = "HTTPS authority host sent by HTTPS requests."; };
      httpsPort = mkOption { type = port; description = "HTTPS port used by HTTPS requests."; };
    };
  };
  requestDefinition = types.submodule {
    options = {
      mode = mkOption { type = types.enum [ "normal" "pgo" "local" ]; description = "Rendered workload mode: normal benchmark, PGO training, or single-user local smoke test."; };
      target = mkOption { type = targetRecord; description = "HTTP and HTTPS endpoint settings for this request."; };
      request = mkOption { type = benchmarkTypes.request; description = "HTTP request and optional response validation settings."; };
      protocol = mkOption { type = benchmarkTypes.protocolRecord; description = "Resolved Hyperfoil protocol and load settings."; };
    };
  };
  render = requestConfig:
    let
      mode = requestConfig.mode;
      protocol = requestConfig.protocol;
      request = requestConfig.request;
      target = requestConfig.target;
      targetSettings = if protocol.protocol == "HTTP1" then {
        url = target.httpUrl;
        authority = "${target.httpAuthority}:${toString target.httpPort}";
        port = target.httpPort;
        allowHttp1x = true;
        allowHttp2 = false;
      } else {
        url = target.httpsUrl;
        authority = "${target.httpsAuthority}:${toString target.httpsPort}";
        port = target.httpsPort;
        allowHttp1x = protocol.protocol != "HTTPS2";
        allowHttp2 = protocol.protocol == "HTTPS2";
      };
      requestHeaders = lib.mapAttrs' (name: value: lib.nameValuePair (lib.toLower name) value) request.requestHeaders;
      requestStep = { withSla, handler }: {
        httpRequest = {
          method = if request.method == null then "GET" else request.method;
          authority = targetSettings.authority;
          path = request.uri;
          headers = requestHeaders // optionalAttrs { "content-type" = request.requestType; };
          sync = true;
        } // optionalAttrs { body = request.requestBody; }
          // lib.optionalAttrs withSla { sla = { limits = protocol.sla; }; }
          // lib.optionalAttrs (handler != null) { handler = handler; };
      };
      scenario = { withSla, handler }: {
        initialSequences = [{ test = [ (requestStep { withSla = withSla; handler = handler; }) ]; }];
      };
      http = {
        host = targetSettings.url;
        port = targetSettings.port;
        allowHttp1x = targetSettings.allowHttp1x;
        allowHttp2 = targetSettings.allowHttp2;
        sharedConnections = protocol.sharedConnections;
        pipeliningLimit = protocol.pipeliningLimit;
        maxHttp2Streams = protocol.maxHttp2Streams;
        connectionStrategy = "SHARED_POOL";
        sslHandshakeTimeout = "1m";
        useHttpCache = false;
      } // lib.optionalAttrs (protocol.protocol != "HTTP1") {
        trustManager = {
          certFile = "/etc/benchmark-tls/ca.pem";
        };
        keyManager = {
          storeType = "PKCS12";
          storeFile = "/etc/benchmark-tls/client.p12";
          password = "password";
        };
      };
      responseHandler = {
        autoRangeCheck = true;
        stopOnInvalid = true;
      } // lib.optionalAttrs (request.responseBody != null) {
        body.check = if request.responseMatchingMode == "EQUAL" then { equalTo = request.responseBody; }
          else if request.responseMatchingMode == "REGEX" then { regex = request.responseBody; }
          else { json = request.responseBody; };
      };
    in if mode == "local" || mode == "pgo" then {
      name = "${mode}-${protocol.protocol}-${request.name}";
      failurePolicy = "CANCEL";
      http = http // { requestTimeout = "30s"; };
      phases = if mode == "local" then [{
        local.atOnce = { users = 1; scenario = scenario { withSla = false; handler = responseHandler; }; };
      }] else [{
        pgo.always = {
          users = 1;
          duration = config.benchmark.hyperfoil.pgoDuration;
          scenario = scenario { withSla = false; handler = responseHandler; };
        };
      }];
    } else
      let
        warmupDuration = config.benchmark.hyperfoil.warmupDuration;
        benchmarkDuration = config.benchmark.hyperfoil.benchmarkDuration;
        sessionLimitFactor = config.benchmark.hyperfoil.sessionLimitFactor;
      in {
      name = "benchmark";
      failurePolicy = "CANCEL";
      agents = { };
      http = http;
      phases = [{
        warmup.always = {
          users = builtins.floor (protocol.compileOps * sessionLimitFactor);
          duration = warmupDuration;
          isWarmup = true;
          scenario = scenario { withSla = false; handler = responseHandler; };
        };
      }] ++ lib.imap0 (index: ops: {
        "main/${toString index}".constantRate = {
          usersPerSec = ops;
          maxSessions = lib.min (builtins.floor (ops * sessionLimitFactor)) protocol.sharedConnections;
          duration = benchmarkDuration;
          isWarmup = false;
          startAfterStrict = if index == 0 then "warmup" else "main/${toString (index - 1)}";
          scenario = scenario { withSla = true; handler = responseHandler; };
        };
      }) protocol.ops;
    };
  benchmarkTarget = {
    httpUrl = "http://10.0.0.2"; httpAuthority = "10.0.0.2"; httpPort = 8080;
    httpsUrl = "https://10.0.0.2"; httpsAuthority = "10.0.0.2"; httpsPort = 8443;
  };
  suite = config.benchmark.suite;
  duplicateNames = names: lib.unique (lib.filter (name: lib.count (candidate: candidate == name) names > 1) names);
  duplicateDocumentNames = duplicateNames (map (request: request.name) suite.documents);
  suiteRequestName = { protocolName, requestName, mode }:
    "${toString (builtins.stringLength protocolName)}-${protocolName}-${toString (builtins.stringLength requestName)}-${requestName}-${mode}";
  suiteRequests = if suiteName == null then { } else
    assert lib.assertMsg (duplicateDocumentNames == [ ]) "Duplicate suite document names: ${lib.concatStringsSep ", " duplicateDocumentNames}";
    lib.listToAttrs (lib.flatten (lib.mapAttrsToList (protocolName: protocol:
    lib.concatMap (request: [
      (lib.nameValuePair (suiteRequestName { protocolName = protocolName; mode = "normal"; requestName = request.name; }) {
        mode = "normal";
        protocol = protocol;
        request = request;
        target = benchmarkTarget;
      })
    ]) suite.documents) suite.resolvedProtocols));
  definitions = if suiteName == null then { } else
    assert lib.assertMsg (validArtifactComponent suiteName) "Unsafe suite name in Hyperfoil artifact path: ${suiteName}";
    assert lib.assertMsg (duplicateDocumentNames == [ ]) "Duplicate suite document names: ${lib.concatStringsSep ", " duplicateDocumentNames}";
    assert lib.assertMsg (suite.statusRequest.name != statusArtifactName) "Reserved status request name: ${statusArtifactName}";
    assert lib.assertMsg (validArtifactComponent suite.statusRequest.name) "Unsafe status request name in Hyperfoil artifact path: ${suite.statusRequest.name}";
    assert lib.assertMsg (!(builtins.any (request: request.name == statusArtifactName) suite.documents)) "Reserved document name: ${statusArtifactName}";
    lib.mapAttrs (protocolName: _: lib.listToAttrs (map (request:
      assert lib.assertMsg (validArtifactComponent protocolName) "Unsafe protocol name in Hyperfoil artifact path: ${protocolName}";
      assert lib.assertMsg (validArtifactComponent request.name) "Unsafe request name in Hyperfoil artifact path: ${request.name}";
      lib.nameValuePair request.name {
        normal = config.benchmark.hyperfoil.rendered.${suiteRequestName { protocolName = protocolName; requestName = request.name; mode = "normal"; }};
      }) suite.documents)) suite.resolvedProtocols;
in {
  options.benchmark.hyperfoil = {
    warmupDuration = mkOption {
      type = benchmarkTypes.duration;
      default = "1m";
      description = "Duration of the Hyperfoil warmup phase.";
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
    requests = mkOption {
      type = types.attrsOf requestDefinition;
      default = { };
      description = "Named Hyperfoil rendering requests, analogous to systemd.services.<name>.";
    };
    rendered = mkOption {
      type = types.attrsOf types.attrs;
      readOnly = true;
      description = "Read-only YAML-compatible Hyperfoil definitions rendered from benchmark.hyperfoil.requests.";
    };
    definitions = mkOption {
      type = types.attrs;
      readOnly = true;
      description = "Generated normal Hyperfoil benchmark definitions for this suite.";
    };
  };

  config = {
    benchmark.hyperfoil = {
      requests = lib.mkIf (suiteName != null) suiteRequests;
      rendered = lib.mapAttrs (name: request:
        assert lib.assertMsg (validArtifactComponent name) "Unsafe Hyperfoil request name: ${name}";
        render request
      ) config.benchmark.hyperfoil.requests;
      definitions = definitions;
    };
  };
}
