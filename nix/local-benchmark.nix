{ pkgs, lib }:
let
  render = import ./render-hyperfoil.nix { inherit lib; };
  hyperfoil = import ./system/hyperfoil.nix { inherit pkgs; };
  target = {
    httpUrl = "http://127.0.0.1";
    httpAuthority = "127.0.0.1";
    httpPort = 8080;
    httpsUrl = "https://localhost";
    httpsAuthority = "localhost";
    httpsPort = 8443;
  };
  definition = { request, protocol, mode ? "local", duration ? "2m" }:
    (pkgs.formats.yaml { }).generate "${mode}-${protocol.protocol}-${request.name}.yaml" (render {
      inherit request mode target;
      settings.pgoDuration = duration;
      protocol = protocol // {
        sharedConnections = 1;
        pipeliningLimit = 1;
        maxHttp2Streams = 1;
      };
    });
  # Aesh restores terminal attributes on exit; detach from the test driver's
  # controlling terminal so job control cannot suspend the standalone JVM.
  # Bound the new process group too; the driver's timeout cannot kill its descendants.
  runDefinition = workload:
    "JAVA_OPTS='-Dio.hyperfoil.jitter.watchdog.threshold=86400000 -Dio.hyperfoil.cpu.watchdog.idle.threshold=0' ${pkgs.util-linux}/bin/setsid --wait ${pkgs.coreutils}/bin/timeout --kill-after=10s 4m ${hyperfoil}/bin/run.sh ${workload} --fail-on-errors < /dev/null";
  runModules = suite: runName: profilingEnabled: runModule: extraModules: [
    ./system/local-vm.nix
    ./system/run.nix
    { imports = suite.config.benchmark.suite.runModules; }
    { benchmark.run.name = runName; }
    {
      benchmark = {
        profiling.enable = lib.mkForce profilingEnabled;
        jvm.args = lib.mkForce [ "-Xms1G" "-Xmx1G" ];
      };
      environment.etc."benchmark-tls".source = import ./tls.nix { inherit pkgs; };
      environment.systemPackages = [ hyperfoil pkgs.jdk25_headless ];
    }
    runModule
  ] ++ extraModules;
in {
  inherit definition runDefinition runModules;

  trainingProfile = { name, suite, runName, runModule, request, protocol }:
    pkgs.testers.runNixOSTest {
      name = "${name}-pgo-profile";
      nodes.machine.imports = runModules suite runName false runModule [{
        benchmark.sut.runtime = lib.mkForce "native-pgo-instrument";
        benchmark.sut.pgoProfile = lib.mkForce null;
        systemd.services.sut.serviceConfig = {
          WorkingDirectory = "/var/lib/sut";
          # Native-image runs shutdown hooks and exits with 128 + SIGTERM.
          SuccessExitStatus = [ 143 ];
          ExecStartPre = [ "${pkgs.coreutils}/bin/rm -f /var/lib/sut/default.iprof" ];
          ExecStopPost = [ "${pkgs.coreutils}/bin/test -s /var/lib/sut/default.iprof" ];
        };
      }];
      testScript = ''
        from datetime import timedelta

        start_all()
        machine.wait_for_unit("multi-user.target")
        machine.succeed("systemctl start sut.service || (journalctl --no-pager -u sut.service; false)")
        machine.wait_for_unit("sut.service")
        try:
            machine.succeed(${builtins.toJSON (runDefinition (definition {
              inherit protocol;
              request = suite.config.benchmark.suite.statusRequest;
            }))})
            machine.succeed(${builtins.toJSON (runDefinition (definition {
              inherit request protocol;
              mode = "pgo";
              duration = suite.config.benchmark.hyperfoil.pgoDuration;
            }))}, timeout=timedelta(minutes=5))
        finally:
            machine.succeed("systemctl stop sut.service")
        machine.succeed("test $(systemctl show sut.service -p Result --value) = success")
        machine.succeed("test -s /var/lib/sut/default.iprof")
        machine.copy_from_machine("/var/lib/sut/default.iprof")
      '';
    };
}
