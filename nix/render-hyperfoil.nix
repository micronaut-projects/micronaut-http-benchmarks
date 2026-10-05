{ lib }:
# Requests and protocols are normalized and validated by the suite modules.
{ mode, protocol, request, target, settings ? { } }:
let
  optionalAttrs = attrs: lib.filterAttrs (_: value: value != null) attrs;
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
  requestStep = { withSla, handler, preflight ? false }: {
    httpRequest = {
      method = if request.method == null then "GET" else request.method;
      authority = targetSettings.authority;
      path = request.uri;
      headers = requestHeaders // optionalAttrs { "content-type" = request.requestType; };
      sync = true;
    } // optionalAttrs { body = request.requestBody; }
      // lib.optionalAttrs (withSla || preflight) {
        # Native validation returns only the first failure within each SLA. Keep latency independent
        # so connection blocking or response errors cannot hide a simultaneous percentile failure.
        sla = if preflight then [ { errorRatio = 0; invalidRatio = 0; blockedRatio = 1; } ] else [
          { limits = protocol.sla; blockedRatio = 1; }
          { errorRatio = 0; invalidRatio = 0; blockedRatio = 0; }
        ];
      }
      // lib.optionalAttrs (handler != null) { handler = handler; };
  };
  scenario = { withSla, handler, preflight ? false }: {
    initialSequences = [{ test = [ (requestStep { inherit withSla handler preflight; }) ]; }];
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
  statusHandler = {
    autoRangeCheck = true;
    stopOnInvalid = true;
  };
  responseHandler = statusHandler // lib.optionalAttrs (request.responseBody != null) {
    body.check = if request.responseMatchingMode == "EQUAL" then { equalTo = request.responseBody; }
      else if request.responseMatchingMode == "REGEX" then { regex = request.responseBody; }
      else { json = request.responseBody; };
  };
  preflightPhase = {
    preflight.atOnce = {
      users = 2;
      maxDuration = "2m";
      isWarmup = true;
      scenario = scenario { withSla = false; preflight = true; handler = responseHandler; };
    };
  };
in if mode == "local" || mode == "pgo" then {
  name = "${mode}-${protocol.protocol}-${request.name}";
  failurePolicy = "CANCEL";
  http = http // { requestTimeout = "30s"; };
  phases = if mode == "local" then [{
    local.atOnce = { users = 1; scenario = scenario { withSla = false; handler = responseHandler; }; };
  }] else [ preflightPhase {
    pgo.always = {
      users = 1;
      duration = settings.pgoDuration;
      startAfterStrict = "preflight";
      scenario = scenario { withSla = false; handler = statusHandler; };
    };
  }];
} else
  let
    warmupDuration = settings.warmupDuration;
    benchmarkDuration = settings.benchmarkDuration;
    sessionLimitFactor = settings.sessionLimitFactor;
  in {
  name = "benchmark";
  failurePolicy = "CANCEL";
  agents = { };
  http = http;
  phases = [ preflightPhase {
    warmup.always = {
      users = settings.warmupUsers or (builtins.floor (protocol.compileOps * sessionLimitFactor));
      duration = warmupDuration;
      isWarmup = true;
      startAfterStrict = "preflight";
      scenario = scenario { withSla = false; handler = statusHandler; };
    };
  }] ++ lib.imap0 (index: ops: {
    "main/${toString index}".constantRate = {
      usersPerSec = ops;
      maxSessions = builtins.ceil (ops * sessionLimitFactor);
      sessionLimitPolicy = "FAIL";
      duration = benchmarkDuration;
      isWarmup = false;
      startAfterStrict = if index == 0 then "warmup" else "main/${toString (index - 1)}";
      scenario = scenario { withSla = true; handler = statusHandler; };
    };
  }) protocol.ops;
}
