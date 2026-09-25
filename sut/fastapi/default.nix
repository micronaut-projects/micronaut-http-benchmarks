{ lib, pkgs, ... }:
{
  imports = [ (import ../python-server {
    name = "fastapi";
    displayName = "FastAPI";
    interface = "asgi";
    defaultServer = "granian";
    application = "app:app";
    packages = [ pkgs.python3Packages.fastapi ];
    appSource = lib.fileset.toSource {
      root = ./.;
      fileset = lib.fileset.unions [ ./app.py ];
    };
  }) ];
}
