{
  description = "Micronaut Framework benchmark infra";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";
    disko.url = "github:nix-community/disko";
    disko.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { nixpkgs, disko, ... }:
  let
    lib = nixpkgs.lib;
    supportedSystems = [ "x86_64-linux" "aarch64-linux" ];
    suts = [
      (import ../sut/pure-netty)
      (import ../sut/micronaut-framework)
      (import ../sut/helidon-nima)
      (import ../sut/spring-boot)
      (import ../sut/vertx)
    ];
    frameworkRunMetadata = map (sut: sut.metadata // {
      nixosConfiguration = sut.name;
    }) suts;

    roles = [
      {
        name = "benchmark-server";
        packageName = "benchmark-bootstrap";
        definition = ./system/benchmark-bootstrap.nix;
        activatable = true;
      }
      {
        name = "relay-server";
        definition = ./system/relay-server.nix;
        activatable = true;
      }
      {
        name = "hyperfoil-agent";
        definition = ./system/hyperfoil-agent.nix;
        activatable = true;
      }
      {
        name = "hyperfoil-controller";
        definition = ./system/hyperfoil-controller.nix;
        activatable = true;
      }
      {
        name = "nginx";
        definition = ./system/nginx.nix;
        activatable = false;
      }
      {
        name = "postgresql";
        definition = ./system/postgresql.nix;
        activatable = false;
      }
    ];

    mkHost = {
      system,
      definition
    }: lib.nixosSystem {
        inherit system;
        modules = [
          disko.nixosModules.disko
          definition
        ];
      };

    mkOciBootstrapImage = system: lib.nixosSystem {
      inherit system;
      modules = [ ./system/oci-bootstrap.nix ];
    };

    ociBootstrapImage = system: (mkOciBootstrapImage system).config.system.build.image;

    roleMetadata = role:
      (mkHost {
        system = "x86_64-linux";
        definition = role.definition;
      }).config.benchmark.oci.instance;

    rolesWithMetadata = map (role: role // {
      instance = roleMetadata role;
    }) roles;

    duplicateNames = names:
      lib.filter (name: lib.count (candidate: candidate == name) names > 1) names;

    metadataAssertions =
      let
        roleNames = map (role: role.name) rolesWithMetadata;
        invalidRoles = lib.filter (role:
          role.instance.shape == ""
          || role.instance.ocpus <= 0
          || role.instance.memoryInGb <= 0
          || (role.instance.diskPerformanceUnits != null && role.instance.diskPerformanceUnits < 0)
          || (role.activatable && role.instance.platform == null)
        ) rolesWithMetadata;
      in
      assert lib.assertMsg (duplicateNames roleNames == [ ])
        "Duplicate OCI instance metadata names: ${lib.concatStringsSep ", " (duplicateNames roleNames)}";
      assert lib.assertMsg (invalidRoles == [ ])
        "Malformed OCI instance metadata for: ${lib.concatStringsSep ", " (map (role: role.name) invalidRoles)}";
      true;

    instanceTypes = lib.listToAttrs (map (role:
      lib.nameValuePair role.name role.instance
    ) rolesWithMetadata);

    benchmarkMetadata = {
      frameworkRuns = frameworkRunMetadata;
      inherit instanceTypes;
    };

    metadataPackage = system: name: value:
      (import nixpkgs { inherit system; }).writeTextFile {
        inherit name;
        text = builtins.toJSON value;
      };

    rolePackage = role:
      lib.nameValuePair "${role.packageName or role.name}-system" (mkHost {
        system = role.instance.platform;
        definition = role.definition;
      }).config.system.build.toplevel;

    sutPackage = sut:
      let
        instance = roleMetadata {
          definition = sut.system;
        };
      in
      lib.nameValuePair "${sut.name}-system" (mkHost {
        system = instance.platform;
        definition = sut.system;
      }).config.system.build.toplevel;

    packagesFor = system:
      let
        activatableRoles = lib.filter (role: role.activatable && role.instance.platform == system) rolesWithMetadata;
        systemSuts = lib.filter (sut: (roleMetadata { definition = sut.system; }).platform == system) suts;
      in
      assert metadataAssertions;
      {
        oci-bootstrap-image = ociBootstrapImage system;
        benchmark-metadata = metadataPackage system "benchmark-metadata.json" benchmarkMetadata;
      }
      // lib.listToAttrs (map rolePackage activatableRoles)
      // lib.listToAttrs (map sutPackage systemSuts);
  in {
    packages = lib.genAttrs supportedSystems packagesFor;
  };
}
