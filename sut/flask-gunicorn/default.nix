{ lib, pkgs, ... }:
let
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
      install -Dm644 ${../micronaut-framework/src/main/resources/server.p12} "$out/share/flask-gunicorn/server.p12"
      ${pkgs.openssl}/bin/openssl pkcs12 -in "$out/share/flask-gunicorn/server.p12" -clcerts -nokeys -passin pass:password -out "$out/share/flask-gunicorn/cert.pem"
      ${pkgs.openssl}/bin/openssl pkcs12 -in "$out/share/flask-gunicorn/server.p12" -nocerts -nodes -passin pass:password -out "$out/share/flask-gunicorn/key.pem"
      rm "$out/share/flask-gunicorn/server.p12"
      chmod 0644 "$out/share/flask-gunicorn/cert.pem" "$out/share/flask-gunicorn/key.pem"
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
