{ lib, pkgs, ... }:
let
  tls = import ../../nix/tls.nix { inherit pkgs; };
  python = pkgs.python3.withPackages (pythonPackages: [
    pythonPackages.flask
    pythonPackages.gunicorn
    pythonPackages.h2
    pythonPackages.gevent
  ]);
  package = pkgs.stdenvNoCC.mkDerivation {
    pname = "flask-gunicorn";
    version = "1.0.0";
    src = lib.fileset.toSource {
      root = ./.;
      fileset = lib.fileset.unions [ ./app.py ./run ];
    };
    nativeBuildInputs = [ pkgs.makeWrapper ];
    installPhase = ''
      install -Dm644 app.py "$out/share/flask-gunicorn/app.py"
      install -Dm644 ${tls}/server.pem "$out/share/flask-gunicorn/cert.pem"
      install -Dm644 ${tls}/server-key.pem "$out/share/flask-gunicorn/key.pem"
      install -Dm755 run "$out/libexec/flask-gunicorn"
      makeWrapper ${pkgs.bash}/bin/bash "$out/bin/flask-gunicorn" \
        --add-flags "$out/libexec/flask-gunicorn" \
        --set APP_DIR "$out/share/flask-gunicorn" \
        --prefix PATH : ${lib.makeBinPath [ python pkgs.curl pkgs.coreutils pkgs.systemd ]}
    '';
  };
in {
  benchmark = {
    sut.runtime = lib.mkForce "python";
    sut.tlsHttp2 = true;
    jvm.enable = false;
    sut = {
      inherit package;
      executable = "flask-gunicorn";
      description = "Flask with Gunicorn benchmark server";
      metadata = {
        typePrefix = "flask-gunicorn";
        parameters = {
          server = "gunicorn";
          interface = "wsgi";
          workersPerProtocol = "6";
          totalWorkers = "12";
          workerClass = "gevent";
          http2Support = "beta";
        };
      };
    };
  };
}
