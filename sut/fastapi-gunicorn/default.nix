{ lib, pkgs, ... }:
let
  tls = import ../../nix/tls.nix { inherit pkgs; };
  # Nixpkgs' 26.0.0 leaves empty ASGI HTTP/2 responses without END_STREAM.
  # Use the upstream fix, including the HTTP/2 flow-control and multiplexing fixes.
  gunicorn = pkgs.python3Packages.gunicorn.overridePythonAttrs (_: rec {
    version = "26.2.2";
    src = pkgs.fetchFromGitHub {
      owner = "benoitc";
      repo = "gunicorn";
      tag = version;
      hash = "sha256-F1zjgh5iCmy3LExWhs0tWRjiqloHEUlD4IqVp7U+ht4=";
    };
  });
  python = pkgs.python3.withPackages (pythonPackages: [
    pythonPackages.fastapi
    gunicorn
    pythonPackages.h2
    pythonPackages.uvloop
  ]);
  package = pkgs.stdenvNoCC.mkDerivation {
    pname = "fastapi-gunicorn";
    version = "1.0.0";
    src = lib.fileset.toSource {
      root = ./.;
      fileset = ./run;
    };
    nativeBuildInputs = [ pkgs.makeWrapper ];
    installPhase = ''
      install -Dm644 ${../fastapi/app.py} "$out/share/fastapi-gunicorn/app.py"
      install -Dm644 ${tls}/server.pem "$out/share/fastapi-gunicorn/cert.pem"
      install -Dm644 ${tls}/server-key.pem "$out/share/fastapi-gunicorn/key.pem"
      install -Dm755 run "$out/libexec/fastapi-gunicorn"
      makeWrapper ${pkgs.bash}/bin/bash "$out/bin/fastapi-gunicorn" \
        --add-flags "$out/libexec/fastapi-gunicorn" \
        --set APP_DIR "$out/share/fastapi-gunicorn" \
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
      executable = "fastapi-gunicorn";
      description = "FastAPI with Gunicorn benchmark server";
      metadata = {
        typePrefix = "fastapi-gunicorn";
        parameters = {
          server = "gunicorn";
          interface = "asgi";
          workersPerProtocol = "6";
          totalWorkers = "12";
          workerClass = "asgi";
          serverVersion = gunicorn.version;
          eventLoop = "uvloop";
          http2Support = "beta";
        };
      };
    };
  };
}
