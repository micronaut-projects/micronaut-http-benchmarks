{ pkgs }:
pkgs.maven.buildMavenPackage {
  pname = "hyperfoil";
  version = "0.30.0-smoke-prs";
  src = pkgs.fetchFromGitHub {
    owner = "yawkat";
    repo = "Hyperfoil";
    rev = "9247c26a44c89e220e9a39209723659b1980fabc";
    hash = "sha256-8n3wx4GOZRkGom+eWMTFjm9k3GfqIWzsiVG4h/inVrc=";
  };
  patches = [
    (pkgs.fetchurl {
      url = "https://github.com/yawkat/Hyperfoil/commit/bdf4a6c1d87ac5a038ab16b566133a429bc9e643.patch";
      hash = "sha256-dlAbxe2AzMApNTybdWrlEiOtk3JJTdIQhHeDInG6gFY=";
    })
    (pkgs.fetchurl {
      url = "https://github.com/yawkat/Hyperfoil/commit/772f59c2db99fde0e667bb47ea5c0ccbb5c32c0a.patch";
      hash = "sha256-t9L7JyAp5pf9tZUiU2Y9yAlRUCkw0bD1/GoUQWykNS4=";
    })
  ];
  mvnJdk = pkgs.jdk25;
  nativeBuildInputs = [ pkgs.jdk25 pkgs.unzip ];
  preBuild = ''
    export PATH=${pkgs.jdk25}/bin:$PATH
  '';
  mvnHash = "sha256-f8D1+a4BcdHUzwnK8V+gAXFu8IHBuyFFwa4LZJfdf4Q=";
  mvnParameters = "-pl distribution -am package -DskipTests";
  installPhase = ''
    mkdir -p $out
    unzip distribution/target/hyperfoil-*.zip -d $out
    mv $out/hyperfoil-*/* $out/
    rmdir $out/hyperfoil-*
  '';
}
