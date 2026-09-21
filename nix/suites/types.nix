{ lib }:
let
  inherit (lib) mkOption types;
  positive = types.addCheck types.int (value: value > 0);
  nonEmpty = type: types.addCheck type (value: value != [ ] && value != { });
  duration = types.addCheck types.str (value: builtins.match "^[0-9]+(ns|us|ms|s|m|h|d)$" value != null);
in {
  inherit duration;

  request = types.submodule {
    options = {
      name = mkOption { type = types.str; description = "Request name used in suite metadata and artifacts."; };
      method = mkOption { type = types.nullOr types.str; default = null; description = "HTTP method, or null for GET."; };
      uri = mkOption { type = types.str; description = "Request URI path and query string."; };
      host = mkOption { type = types.nullOr types.str; default = null; description = "Host header, or null for example.com."; };
      requestType = mkOption { type = types.nullOr types.str; default = null; description = "Content-Type header, or null to omit it."; };
      requestHeaders = mkOption { type = types.attrsOf types.str; default = { }; description = "Additional HTTP request headers."; };
      requestBody = mkOption { type = types.nullOr types.str; default = null; description = "Optional HTTP request body."; };
      responseBody = mkOption { type = types.nullOr types.str; default = null; description = "Optional expected response body."; };
      responseMatchingMode = mkOption {
        type = types.nullOr (types.enum [ "EQUAL" "JSON" "REGEX" ]);
        default = null;
        description = "Response body matching mode; null retains Hyperfoil JSON matching for a configured response body.";
      };
    };
  };

  protocolRecord = types.submodule {
    options = {
      protocol = mkOption { type = types.enum [ "HTTP1" "HTTPS1" "HTTPS2" ]; description = "HTTP protocol used by the request."; };
      sharedConnections = mkOption { type = positive; description = "Number of shared Hyperfoil connections."; };
      pipeliningLimit = mkOption { type = positive; default = 1; description = "Maximum HTTP/1 pipeline depth."; };
      maxHttp2Streams = mkOption { type = positive; default = 1; description = "Maximum concurrent HTTP/2 streams."; };
      compileOps = mkOption { type = positive; description = "Request rate used to size normal benchmark warmup."; };
      ops = mkOption { type = nonEmpty (types.listOf positive); description = "Ordered request rates for normal benchmark phases."; };
      sla = mkOption { type = nonEmpty (types.attrsOf duration); description = "Hyperfoil SLA percentile limits using native duration strings."; };
    };
  };
}
