{ name, displayName, appSource, packages, interface, application ? "app:app",
  defaultServer ? "granian", granianInterface ? interface, eventLoop ? "uvloop" }:
{ config, lib, pkgs, ... }:
let
  server = config.benchmark.python.server;
  effectiveInterface = if server == "granian" then granianInterface else interface;
  tls = import ../../nix/tls.nix { inherit pkgs; };
  # Nixpkgs' 26.0.0 leaves empty ASGI HTTP/2 responses without END_STREAM.
  # Keep the existing WSGI version; ASGI needs the upstream fix.
  gunicorn = if interface == "wsgi" then pkgs.python3Packages.gunicorn else
    pkgs.python3Packages.gunicorn.overridePythonAttrs (_: rec {
      version = "26.2.2";
      src = pkgs.fetchFromGitHub {
        owner = "benoitc";
        repo = "gunicorn";
        tag = version;
        hash = "sha256-F1zjgh5iCmy3LExWhs0tWRjiqloHEUlD4IqVp7U+ht4=";
      };
    });
  serverPackage = if server == "gunicorn" then gunicorn else pkgs.python3Packages.granian;
  python = pkgs.python3.withPackages (pythonPackages: packages ++ [ serverPackage ]
    ++ lib.optionals (server == "gunicorn") [ pythonPackages.h2 ]
    ++ lib.optionals (server == "gunicorn" && interface == "wsgi") [ pythonPackages.gevent ]
    ++ lib.optionals (interface == "asgi" && eventLoop == "uvloop") [ pythonPackages.uvloop ]);
  package = pkgs.stdenvNoCC.mkDerivation {
    pname = "${name}-${server}";
    version = "1.0.0";
    src = appSource;
    nativeBuildInputs = [ pkgs.makeWrapper ];
    installPhase = ''
      runHook preInstall
      mkdir -p "$out/share/${name}"
      cp -r ./. "$out/share/${name}/"
      install -Dm644 ${tls}/server.pem "$out/share/${name}/cert.pem"
      install -Dm644 ${tls}/server-key.pem "$out/share/${name}/key.pem"
      install -Dm755 ${./run} "$out/libexec/${name}"
      makeWrapper ${pkgs.bash}/bin/bash "$out/bin/${name}" \
        --add-flags "$out/libexec/${name}" \
        --set APP_DIR "$out/share/${name}" \
        --set PYTHON_SERVER ${lib.escapeShellArg server} \
        --set PYTHON_INTERFACE ${lib.escapeShellArg effectiveInterface} \
        --set PYTHON_EVENT_LOOP ${lib.escapeShellArg eventLoop} \
        --set PYTHON_APPLICATION ${lib.escapeShellArg application} \
        --prefix PATH : ${lib.makeBinPath [ python pkgs.curl pkgs.coreutils pkgs.systemd ]}
      runHook postInstall
    '';
  };
in {
  options.benchmark.python.server = lib.mkOption {
    type = lib.types.enum [ "gunicorn" "granian" ];
    default = defaultServer;
    description = "HTTP server mode for the Python framework SUT.";
  };

  config.benchmark = {
    sut.runtime = lib.mkForce "python";
    sut.tlsHttp2 = true;
    jvm.enable = false;
    sut = {
      inherit package;
      executable = name;
      description = "${displayName} with ${server} benchmark server";
      metadata = {
        typePrefix = name;
        parameters = {
          inherit server;
          serverVersion = serverPackage.version;
          interface = effectiveInterface;
          workersPerProtocol = "6";
          totalWorkers = "12";
        } // lib.optionalAttrs (interface == "asgi") {
          inherit eventLoop;
        } // lib.optionalAttrs (server == "gunicorn") {
          workerClass = if interface == "wsgi" then "gevent" else "asgi";
          http2Support = "beta";
        };
      };
    };
  };
}
