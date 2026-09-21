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
      duration = settings.pgoDuration;
      scenario = scenario { withSla = false; handler = responseHandler; };
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
}
