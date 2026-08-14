{ pkgs, ... }:
{
  benchmark = {
    jvm.enable = true;
    sut = {
      package = pkgs.callPackage ./package.nix { };
      executable = "spring-boot";
      description = "Spring Boot benchmark server";
      environment = [ "SPRING_SYSTEMD_NOTIFY_ENABLED=true" ];
      metadata = {
        type = "spring-boot-hotspot";
        parameters.runtime = "Nix-packaged JDK 25";
      };
    };
  };
}
