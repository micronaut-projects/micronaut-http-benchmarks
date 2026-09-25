{ lib, pkgs, ... }:
{
  imports = [ (import ../python-server {
    name = "django";
    displayName = "Django";
    interface = "wsgi";
    defaultServer = "gunicorn";
    application = "wsgi:application";
    packages = [ pkgs.python3Packages.django ];
    appSource = lib.fileset.toSource {
      root = ./.;
      fileset = lib.fileset.unions [ ./settings.py ./urls.py ./wsgi.py ];
    };
  }) ];
}
