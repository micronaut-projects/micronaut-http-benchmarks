{ lib, pkgs, ... }:
let
  tls = import ../../nix/tls.nix { inherit pkgs; };
  python = pkgs.python3.withPackages (pythonPackages: [
    pythonPackages.fastapi
    pythonPackages.granian
    pythonPackages.uvloop
  ]);
  package = pkgs.stdenvNoCC.mkDerivation {
    pname = "fastapi-granian";
    version = "1.0.0";
    src = lib.fileset.toSource {
      root = ./.;
      fileset = ./run;
    };
    nativeBuildInputs = [ pkgs.makeWrapper ];
    installPhase = ''
      install -Dm644 ${../fastapi/app.py} "$out/share/fastapi-granian/app.py"
      install -Dm644 ${tls}/server.pem "$out/share/fastapi-granian/cert.pem"
      install -Dm644 ${tls}/server-key.pem "$out/share/fastapi-granian/key.pem"
      install -Dm755 run "$out/libexec/fastapi-granian"
      makeWrapper ${pkgs.bash}/bin/bash "$out/bin/fastapi-granian" \
        --add-flags "$out/libexec/fastapi-granian" \
        --set APP_DIR "$out/share/fastapi-granian" \
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
      executable = "fastapi-granian";
      description = "FastAPI with Granian benchmark server";
      metadata = {
        typePrefix = "fastapi-granian";
        parameters = {
          server = "granian";
          interface = "asgi";
          workersPerProtocol = "6";
          totalWorkers = "12";
          eventLoop = "uvloop";
        };
      };
    };
  };
}
