{ pkgs, ... }:
let
  hyperfoil = pkgs.stdenvNoCC.mkDerivation {
    pname = "hyperfoil";
    version = "0.29.3";

    src = pkgs.fetchurl {
      url = "https://github.com/Hyperfoil/Hyperfoil/releases/download/hyperfoil-all-0.29.3/hyperfoil-0.29.3.zip";
      hash = "sha256-7WycKFNYDDDQU/u5wCIlu/pfK7X27ILF6sfE25gSMrQ=";
    };

    nativeBuildInputs = [ pkgs.unzip ];

    installPhase = ''
      mkdir -p "$out"
      cp -r . "$out/"
    '';
  };

  controllerService = pkgs.writeShellScript "hyperfoil-controller-service" ''
    install -d -m 0700 /home/hyperfoil/.ssh
    ln -sfn "$CREDENTIALS_DIRECTORY/id_rsa" /home/hyperfoil/.ssh/id_rsa
    export PATH=${pkgs.jdk25_headless}/bin:$PATH
    exec ${hyperfoil}/bin/controller.sh -Djgroups.join_timeout=20000
  '';

  controllerReady = pkgs.writeShellScript "hyperfoil-controller-ready" ''
    deadline=$((SECONDS + 120))
    while [ "$SECONDS" -lt "$deadline" ]; do
      if [ "$(${pkgs.curl}/bin/curl --silent --show-error --output /dev/null --write-out '%{http_code}' --connect-timeout 2 --max-time 5 http://localhost:8090/)" = 200 ]; then
        exit 0
      fi
      ${pkgs.coreutils}/bin/sleep 1
    done
    exit 1
  '';
in
{
  imports = [
    ./base.nix
  ];

  users.users.hyperfoil = {
    isNormalUser = true;
    home = "/home/hyperfoil";
    createHome = true;
  };

  networking.firewall.allowedTCPPorts = [ 7800 ];

  system.build.hyperfoil-controller = hyperfoil;

  systemd.services.hyperfoil-controller = {
    description = "Hyperfoil controller";
    after = [ "network-online.target" ];
    wants = [ "network-online.target" ];
    wantedBy = [ "multi-user.target" ];

    serviceConfig = {
      Type = "simple";
      ExecStart = controllerService;
      ExecStartPost = controllerReady;
      LoadCredential = [ "id_rsa:/etc/credstore/hyperfoil-controller/id_rsa" ];
      User = "hyperfoil";
      WorkingDirectory = "/home/hyperfoil";
      TimeoutStartSec = 130;
    };
  };

  systemd.services.sshd = {
    requires = [ "hyperfoil-controller.service" ];
    after = [ "hyperfoil-controller.service" ];
  };
}
