{ lib, pkgs, ... }:
let
  tls = import ../../nix/tls.nix { inherit pkgs; };
  python = pkgs.python3;
  emmett-core-src = python.pkgs.fetchPypi {
    pname = "emmett_core";
    version = "1.4.1";
    hash = "sha256-ayLN2w41pF5RfptIIatyFH4z7WuvfYZf+eQoArErvb4=";
  };
  emmett-core = python.pkgs.buildPythonPackage rec {
    pname = "emmett-core";
    version = "1.4.1";
    pyproject = true;
    src = emmett-core-src;
    nativeBuildInputs = with pkgs.rustPlatform; [ cargoSetupHook maturinBuildHook ];
    cargoDeps = pkgs.rustPlatform.fetchCargoVendor {
      src = emmett-core-src;
      hash = "sha256-nPvcm4PQ+RyCiGfmUZ5w3pHzzGE7nTqmJaXuy9gkH5Q=";
    };
    doCheck = false;
  };
  renoir = python.pkgs.buildPythonPackage rec {
    pname = "renoir";
    version = "1.6.1";
    pyproject = true;
    src = python.pkgs.fetchPypi {
      inherit pname version;
      hash = "sha256-6tBNPI17tASMbZkkVhDKikezqDzcoWt+TzqPZK0IQIY=";
    };
    build-system = [ python.pkgs.poetry-core ];
    doCheck = false;
  };
  severus = python.pkgs.buildPythonPackage rec {
    pname = "severus";
    version = "1.1.1";
    pyproject = true;
    src = python.pkgs.fetchPypi {
      pname = "Severus";
      inherit version;
      hash = "sha256-C2l4RZE8DUjPXssRH4vDQQzuUYcxqiucmkFpYats22E=";
    };
    build-system = [ python.pkgs.poetry-core ];
    dependencies = [ python.pkgs.pyyaml ];
    dontCheckRuntimeDeps = true;
    doCheck = false;
  };
  emmett-pydal = python.pkgs.buildPythonPackage rec {
    pname = "emmett-pydal";
    version = "17.3.3";
    pyproject = true;
    src = python.pkgs.fetchPypi {
      pname = "emmett_pydal";
      inherit version;
      hash = "sha256-N+WEyUux2RcxuJW+ATW472dN7gURM8v4NaX9gggACb4=";
    };
    build-system = [ python.pkgs.hatchling ];
    doCheck = false;
  };
  emmett = python.pkgs.buildPythonPackage rec {
    pname = "emmett";
    version = "2.8.1";
    pyproject = true;
    src = python.pkgs.fetchPypi {
      inherit pname version;
      hash = "sha256-H1gktPMpwGvYhoA2xajGSjx2qRoI0ncBt1brDgyYDvw=";
    };
    build-system = [ python.pkgs.hatchling ];
    dependencies = [
      python.pkgs.click
      emmett-core
      python.pkgs.pendulum
      emmett-pydal
      python.pkgs.pyyaml
      renoir
      severus
    ];
    doCheck = false;
  };
  runtime = python.withPackages (_: [ emmett pkgs.python3Packages.granian pkgs.python3Packages.orjson ]);
  package = pkgs.stdenvNoCC.mkDerivation {
    pname = "emmett-granian";
    version = "1.0.0";
    src = lib.fileset.toSource {
      root = ./.;
      fileset = lib.fileset.unions [ ./app.py ./run ];
    };
    nativeBuildInputs = [ pkgs.makeWrapper ];
    installPhase = ''
      install -Dm644 app.py "$out/share/emmett-granian/app.py"
      install -Dm644 ${tls}/server.pem "$out/share/emmett-granian/cert.pem"
      install -Dm644 ${tls}/server-key.pem "$out/share/emmett-granian/key.pem"
      install -Dm755 run "$out/libexec/emmett-granian"
      makeWrapper ${pkgs.bash}/bin/bash "$out/bin/emmett-granian" \
        --add-flags "$out/libexec/emmett-granian" \
        --set APP_DIR "$out/share/emmett-granian" \
        --prefix PATH : ${lib.makeBinPath [ runtime pkgs.curl pkgs.coreutils pkgs.systemd ]}
    '';
  };
in {
  benchmark = {
    sut.runtime = lib.mkForce "python";
    sut.tlsHttp2 = true;
    jvm.enable = false;
    sut = {
      inherit package;
      executable = "emmett-granian";
      description = "Emmett with Granian benchmark server";
      metadata = {
        typePrefix = "emmett-granian";
        parameters = {
          server = "granian";
          interface = "rsgi";
          workersPerProtocol = "6";
          totalWorkers = "12";
        };
      };
    };
  };
}
