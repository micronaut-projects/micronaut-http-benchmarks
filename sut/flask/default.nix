{ lib, pkgs, ... }:
{
  imports = [ (import ../python-server {
    name = "flask";
    displayName = "Flask";
    interface = "wsgi";
    defaultServer = "gunicorn";
    application = "app:app";
    packages = [ pkgs.python3Packages.flask ];
    appSource = lib.fileset.toSource {
      root = ./.;
      fileset = lib.fileset.unions [ ./app.py ];
    };
  }) ];
}
