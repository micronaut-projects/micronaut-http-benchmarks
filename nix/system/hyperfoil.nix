{ pkgs }:
pkgs.stdenvNoCC.mkDerivation {
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
}
