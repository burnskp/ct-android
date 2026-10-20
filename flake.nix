{
  description = "Couch Tasks Android development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    treefmt-nix = {
      url = "github:numtide/treefmt-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    git-hooks-nix = {
      url = "github:cachix/git-hooks.nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    inputs@{ self, nixpkgs, ... }:
    let
      systems = [ "x86_64-linux" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
      mkPkgs =
        system:
        import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

      mkDevShell =
        { pkgs
        , name
        , preCommitCheck
        ,
        }:
        let
          androidPackages = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "36" ];
            buildToolsVersions = [ "36.0.0" ];
          };
          androidSdk = androidPackages.androidsdk;
        in
        pkgs.mkShell {
          inherit name;
          JAVA_HOME = "${pkgs.jdk17}/lib/openjdk";
          ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/36.0.0/aapt2";

          packages = [
            androidSdk
            pkgs.gradle
            pkgs.jdk17
            pkgs.kotlin
            pkgs.gnumake
            pkgs.git
            pkgs.python3
          ]
          ++ preCommitCheck.enabledPackages;
          shellHook = preCommitCheck.shellHook + ''
            export GRADLE_USER_HOME="$PWD/.gradle"
            mkdir -p "$GRADLE_USER_HOME"
          '';
        };
    in
    {
      formatter = forAllSystems (
        system:
        let
          pkgs = mkPkgs system;
          treefmtEval = inputs."treefmt-nix".lib.evalModule pkgs ./treefmt.nix;
        in
        treefmtEval.config.build.wrapper
      );

      checks = forAllSystems (
        system:
        let
          pkgs = mkPkgs system;
          treefmtEval = inputs."treefmt-nix".lib.evalModule pkgs ./treefmt.nix;
        in
        {
          formatting = treefmtEval.config.build.check self;
          pre-commit-check = inputs."git-hooks-nix".lib.${system}.run {
            src = ./.;
            imports = [ ./git-hooks.nix ];
          };
        }
      );

      devShells = forAllSystems (
        system:
        let
          pkgs = mkPkgs system;
          preCommitCheck = self.checks.${system}.pre-commit-check;
        in
        {
          default = mkDevShell {
            inherit pkgs preCommitCheck;
            name = "couch-tasks-android-dev";
          };
        }
      );
    };
}
