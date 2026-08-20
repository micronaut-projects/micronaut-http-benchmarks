{ pkgs, lib, ... }:
let
  gradle = pkgs.gradle_9.override {
    java = pkgs.jdk25_headless;
    javaToolchains = [ pkgs.jdk25_headless ];
  };
  relayAgent = pkgs.stdenv.mkDerivation (finalAttrs: {
    pname = "relay-agent";
    version = "unstable";

    src = lib.fileset.toSource {
      root = ../..;
      fileset = lib.fileset.gitTracked ../..;
    };

    nativeBuildInputs = [
      gradle
      pkgs.makeWrapper
    ];

    mitmCache = gradle.fetchDeps {
      pkg = finalAttrs.finalPackage;
      data = ./relay-agent-deps.json;
      silent = false;
      useBwrap = false;
    };

    gradleBuildTask = ":relay-agent:installDist";
    gradleUpdateTask = finalAttrs.gradleBuildTask;

    installPhase = ''
      mkdir -p "$out/bin" "$out/share/relay-agent"
      cp -r relay-agent/build/install/relay-agent/lib "$out/share/relay-agent/"
      makeWrapper ${pkgs.jdk25_headless}/bin/java "$out/bin/relay-agent" \
        --add-flags "-cp $out/share/relay-agent/lib/* io.micronaut.benchmark.relay.agent.Main"
    '';
  });

  relayAgentService = pkgs.writeShellScript "relay-agent-service" ''
    exec ${pkgs.jdk25_headless}/bin/java \
      "-Dkey.algorithm=$(<"$CREDENTIALS_DIRECTORY/key-algorithm")" \
      "-Dkey=$(<"$CREDENTIALS_DIRECTORY/key")" \
      "-Dcert=$(<"$CREDENTIALS_DIRECTORY/cert")" \
      "-Dremote-cert=$(<"$CREDENTIALS_DIRECTORY/remote-cert")" \
      -Dport=8443 \
      -Dlog-port=8444 \
      -cp '${relayAgent}/share/relay-agent/lib/*' \
      io.micronaut.benchmark.relay.agent.Main
  '';
in
{
  imports = [
    ./base.nix
  ];

  benchmark.oci.instance = {
    shape = "VM.Standard.E4.Flex";
    ocpus = 4;
    memoryInGb = 8;
    platform = "x86_64-linux";
    diskPerformanceUnits = 80;
  };

  nix.settings.sandbox = "relaxed";

  networking.firewall.allowedTCPPorts = [ 8443 ];

  benchmark.roleUnits = [ "relay-agent.service" ];

  system.build.relay-agent = relayAgent;

  systemd.services.relay-agent = {
    description = "Micronaut Framework benchmark relay agent";
    after = [ "network-online.target" ];
    wants = [ "network-online.target" ];
    serviceConfig = {
      Type = "simple";
      ExecStart = relayAgentService;
      LoadCredential = [
        "key-algorithm:/etc/credstore/relay-agent/key-algorithm"
        "key:/etc/credstore/relay-agent/key"
        "cert:/etc/credstore/relay-agent/cert"
        "remote-cert:/etc/credstore/relay-agent/remote-cert"
      ];
      Restart = "on-failure";
    };
  };
}
