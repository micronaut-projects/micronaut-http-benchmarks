{ config, lib, ... }@moduleArgs:
let
  inherit (lib) mkOption types;
  benchmarkTypes = import ./suites/types.nix { inherit lib; };
  suiteName = moduleArgs.suiteName or null;
  placeholderPrefix = "@@HYPERFOIL_";
  placeholder = name: "${placeholderPrefix}${name}@@";
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
      inherit (requestConfig) mode protocol request target;
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
          // lib.optionalAttrs (handler != null) { inherit handler; };
      };
      scenario = { withSla, handler }: {
        initialSequences = [{ test = [ (requestStep { inherit withSla handler; }) ]; }];
      };
      http = {
        host = targetSettings.url;
        port = targetSettings.port;
        inherit (targetSettings) allowHttp1x allowHttp2;
        inherit (protocol) sharedConnections pipeliningLimit maxHttp2Streams;
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
      mainPhases = [{
        warmup.always = {
          users = placeholder "WARMUP_SESSIONS";
          duration = placeholder "WARMUP_DURATION";
          isWarmup = true;
          scenario = scenario { withSla = false; handler = responseHandler; };
        };
      }] ++ lib.imap0 (index: ops: {
        "main/${toString index}".constantRate = {
          usersPerSec = ops;
          maxSessions = placeholder "MAIN_${toString index}_SESSIONS";
          duration = placeholder "BENCHMARK_DURATION";
          isWarmup = false;
          startAfterStrict = if index == 0 then "warmup" else "main/${toString (index - 1)}";
          scenario = scenario { withSla = true; handler = responseHandler; };
        };
      }) protocol.ops;
    in if mode == "local" then {
      name = "local-${protocol.protocol}-${request.name}";
      failurePolicy = "CANCEL";
      http = http // { requestTimeout = "30s"; };
      phases = [{ local.atOnce = { users = 1; scenario = scenario { withSla = false; handler = responseHandler; }; }; }];
    } else {
      name = placeholder "NAME";
      failurePolicy = "CANCEL";
      agents = { };
      inherit http;
      phases = if mode == "pgo" then [{
        pgo.constantRate = {
          usersPerSec = protocol.compileOps;
          maxSessions = placeholder "PGO_SESSIONS";
          duration = placeholder "PGO_DURATION";
          isWarmup = false;
          scenario = scenario { withSla = false; handler = null; };
        };
      }] else mainPhases;
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
      (lib.nameValuePair (suiteRequestName { inherit protocolName; mode = "normal"; requestName = request.name; }) {
        mode = "normal";
        inherit protocol request;
        target = benchmarkTarget;
      })
      (lib.nameValuePair (suiteRequestName { inherit protocolName; mode = "pgo"; requestName = request.name; }) {
        mode = "pgo";
        inherit protocol request;
        target = benchmarkTarget;
      })
    ]) suite.documents) suite.resolvedProtocols));
  definitions = if suiteName == null then { } else
    assert lib.assertMsg (validArtifactComponent suiteName) "Unsafe suite name in Hyperfoil artifact path: ${suiteName}";
    assert lib.assertMsg (duplicateDocumentNames == [ ]) "Duplicate suite document names: ${lib.concatStringsSep ", " duplicateDocumentNames}";
    assert lib.assertMsg (suite.statusRequest.name != statusArtifactName) "Reserved status request name: ${statusArtifactName}";
    assert lib.assertMsg (validArtifactComponent suite.statusRequest.name) "Unsafe status request name in Hyperfoil artifact path: ${suite.statusRequest.name}";
    assert lib.assertMsg (!(lib.hasInfix placeholderPrefix (builtins.toJSON suite.statusRequest))) "Reserved Hyperfoil placeholder prefix in status request";
    assert lib.assertMsg (!(builtins.any (request: request.name == statusArtifactName) suite.documents)) "Reserved document name: ${statusArtifactName}";
    lib.mapAttrs (protocolName: _: lib.listToAttrs (map (request:
      assert lib.assertMsg (validArtifactComponent protocolName) "Unsafe protocol name in Hyperfoil artifact path: ${protocolName}";
      assert lib.assertMsg (validArtifactComponent request.name) "Unsafe request name in Hyperfoil artifact path: ${request.name}";
      assert lib.assertMsg (!(lib.hasInfix placeholderPrefix (builtins.toJSON request))) "Reserved Hyperfoil placeholder prefix in request ${request.name}";
      lib.nameValuePair request.name {
        normal = config.benchmark.hyperfoil.rendered.${suiteRequestName { inherit protocolName; requestName = request.name; mode = "normal"; }};
        pgo = config.benchmark.hyperfoil.rendered.${suiteRequestName { inherit protocolName; requestName = request.name; mode = "pgo"; }};
      }) suite.documents)) suite.resolvedProtocols;
in {
  options.benchmark.hyperfoil = {
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
      description = "Generated normal and PGO Hyperfoil benchmark definitions for this suite.";
    };
  };

  config = {
    benchmark.hyperfoil = {
      requests = lib.mkIf (suiteName != null) suiteRequests;
      rendered = lib.mapAttrs (name: request:
        assert lib.assertMsg (validArtifactComponent name) "Unsafe Hyperfoil request name: ${name}";
        render request
      ) config.benchmark.hyperfoil.requests;
      inherit definitions;
    };
  };
}
