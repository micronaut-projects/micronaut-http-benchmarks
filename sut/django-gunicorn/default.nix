{ lib, pkgs, ... }:
let
  tls = import ../../nix/tls.nix { inherit pkgs; };
  python = pkgs.python3.withPackages (pythonPackages: [
    pythonPackages.django
    pythonPackages.gunicorn
    pythonPackages.h2
    pythonPackages.gevent
  ]);
  package = pkgs.stdenvNoCC.mkDerivation {
    pname = "django-gunicorn";
    version = "1.0.0";
    src = lib.fileset.toSource {
      root = ./.;
      fileset = lib.fileset.unions [ ./settings.py ./urls.py ./wsgi.py ./run ];
    };
    nativeBuildInputs = [ pkgs.makeWrapper ];
    installPhase = ''
      install -Dm644 settings.py "$out/share/django-gunicorn/settings.py"
      install -Dm644 urls.py "$out/share/django-gunicorn/urls.py"
      install -Dm644 wsgi.py "$out/share/django-gunicorn/wsgi.py"
      install -Dm644 ${tls}/server.pem "$out/share/django-gunicorn/cert.pem"
      install -Dm644 ${tls}/server-key.pem "$out/share/django-gunicorn/key.pem"
      install -Dm755 run "$out/libexec/django-gunicorn"
      makeWrapper ${pkgs.bash}/bin/bash "$out/bin/django-gunicorn" \
        --add-flags "$out/libexec/django-gunicorn" \
        --set APP_DIR "$out/share/django-gunicorn" \
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
      executable = "django-gunicorn";
      description = "Django with Gunicorn benchmark server";
      metadata = {
        typePrefix = "django-gunicorn";
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
