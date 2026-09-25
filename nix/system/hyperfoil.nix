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
    ./hyperfoil-write-failure-logging.patch
    (pkgs.fetchurl {
      url = "https://github.com/yawkat/Hyperfoil/commit/bdf4a6c1d87ac5a038ab16b566133a429bc9e643.patch";
      hash = "sha256-dlAbxe2AzMApNTybdWrlEiOtk3JJTdIQhHeDInG6gFY=";
    })
    (pkgs.fetchurl {
      url = "https://github.com/yawkat/Hyperfoil/commit/772f59c2db99fde0e667bb47ea5c0ccbb5c32c0a.patch";
      hash = "sha256-t9L7JyAp5pf9tZUiU2Y9yAlRUCkw0bD1/GoUQWykNS4=";
    })
    # https://github.com/Hyperfoil/Hyperfoil/pull/905
    (pkgs.fetchurl {
      url = "https://github.com/yawkat/Hyperfoil/commit/ade5ebd33b77c9f6d06c3535394b833a4f02d977.patch";
      hash = "sha256-iSFpURHoKA9f5Ga3eezn9MxZSqGLIH/x1L/ZIY2vPZw=";
    })
    (pkgs.fetchurl {
      url = "https://github.com/Hyperfoil/Hyperfoil/pull/907.patch";
      hash = "sha256-LzLLj3fD8xYL4cesT8lpi23OrJXW/a+Q8XeL7TlqDq0=";
    })
  ];
  mvnJdk = pkgs.jdk25;
  nativeBuildInputs = [ pkgs.jdk25 pkgs.unzip ];
  preBuild = ''
    export PATH=${pkgs.jdk25}/bin:$PATH
  '';
  mvnHash = "sha256-f8D1+a4BcdHUzwnK8V+gAXFu8IHBuyFFwa4LZJfdf4Q=";
  mvnParameters = "-pl distribution -am -DskipTests";
  installPhase = ''
    mkdir -p $out
    unzip distribution/target/hyperfoil-*.zip -d $out
    mv $out/hyperfoil-*/* $out/
    rmdir $out/hyperfoil-*
  '';
}
