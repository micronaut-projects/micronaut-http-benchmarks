{ pkgs, lib, modulesPath, ... }:
let
  serialTty = if pkgs.stdenv.hostPlatform.isAarch64 then "ttyAMA0" else "ttyS0";
  serialDevice = "/dev/${serialTty}";
in
{
  nixpkgs.config.allowUnfree = true;

  imports = [
    (modulesPath + "/profiles/minimal.nix")
    (modulesPath + "/virtualisation/oci-image.nix")
  ];

  # Benchmark servers are ephemeral; batch background metadata and writeback I/O.
  nix.settings.fsync-metadata = false;
  boot.kernel.sysctl = {
    "vm.dirty_writeback_centisecs" = 1500;
    "vm.dirty_expire_centisecs" = 3000;
    # for profiling
    "kernel.perf_event_paranoid" = 1;
    "kernel.kptr_restrict" = 0;
  };

  services.cloud-init = {
    enable = true;
    network.enable = true;
    settings.datasource_list = [ "Oracle" ];
  };

  services.journald = {
    storage = "persistent";
    console = serialDevice;
    rateLimitInterval = "0";
    rateLimitBurst = 0;
  };

  boot.kernelParams = [ "console=${serialTty},115200n8" ];

  systemd.services = {
    cloud-final = {
      serviceConfig.TimeoutSec = lib.mkForce "15min";
      unitConfig.OnFailure = [ "cloud-final-failure.service" ];
    };

    cloud-final-failure = {
      serviceConfig = {
        Type = "oneshot";
        StandardOutput = "journal+console";
        ExecStart = pkgs.writeShellScript "cloud-final-failure" ''
          {
            printf '%s\n' 'MICRONAUT_BENCHMARK_CLOUD_INIT_FAILED'
            printf '%s\n' '--- /var/log/cloud-init-output.log (tail) ---'
            tail -n 100 /var/log/cloud-init-output.log 2>&1 || :
            printf '%s\n' '--- /var/log/cloud-init.log (tail) ---'
            tail -n 100 /var/log/cloud-init.log 2>&1 || :
            printf '%s\n' '--- cloud-final.service journal (tail) ---'
            ${pkgs.systemd}/bin/journalctl --no-pager -u cloud-final.service -n 100 2>&1 || :
          } > ${serialDevice} 2>&1
          sync || :
          exec ${pkgs.systemd}/bin/systemctl --force poweroff --no-wall
        '';
      };
    };
  };

  services.openssh = {
    enable = true;
    settings.PasswordAuthentication = false;
    settings.PermitRootLogin = "prohibit-password";
  };

  systemd.services.sshd.wantedBy = lib.mkForce [ ];

  networking = {
    dhcpcd.enable = false;
    nftables.enable = true;
    useNetworkd = true;
    firewall = {
      enable = true;
      allowedTCPPorts = [ 22 ];
    };
  };

  systemd.network = {
    enable = true;
    wait-online.extraArgs = [ "--dns" ];
  };

  boot.loader = {
    systemd-boot.enable = false;
    grub.enable = true;
    efi.canTouchEfiVariables = false;
  };

  nix.settings.sandbox = "relaxed";

  environment.enableAllTerminfo = true;
}
