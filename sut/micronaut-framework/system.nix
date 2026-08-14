{ pkgs, ... }:
let
  micronautFramework = pkgs.callPackage ./package.nix { };
in
{
  imports = [
    ../../nix/system/benchmark-bootstrap.nix
  ];

  networking.firewall.allowedTCPPorts = [ 8080 8443 ];

  systemd.services.micronaut-framework = {
    description = "Framework-based benchmark server";
    after = [ "network-online.target" ];
    wants = [ "network-online.target" ];

    serviceConfig = {
      Type = "notify";
      NotifyAccess = "all";
      ExecStart = "${micronautFramework}/bin/micronaut-framework";
      Environment = [
        "MICRONAUT_SYSTEMD_NOTIFY_ENABLED=true"
        "JAVA_TOOL_OPTIONS=-XX:+UseZGC -Xms12G -Xmx12G -Dio.netty.iouring.iosqeAsyncThreshold=2147483647 -Dio.netty.iouring.ringSize=8192 -Djdk.trackAllThreads=false -XX:+UnlockExperimentalVMOptions --add-opens=java.base/java.lang=ALL-UNNAMED"
      ];
      DynamicUser = true;
      Restart = "no";
      StandardOutput = "journal";
      StandardError = "journal";
      TimeoutStartSec = 130;
    };
  };
}
