{ lib, pkgs, ... }:
{
  imports = [ (import ../python-server {
    name = "emmett";
    displayName = "Emmett";
    interface = "asgi";
    defaultServer = "granian";
    application = "app:app";
    granianInterface = "rsgi";
    eventLoop = "asyncio";
    packages = [ (import ./package.nix { inherit pkgs; }) pkgs.python3Packages.orjson ];
    appSource = lib.fileset.toSource {
      root = ./.;
      fileset = lib.fileset.unions [ ./app.py ];
    };
  }) ];
}
