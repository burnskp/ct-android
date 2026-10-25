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
      mkPkgs = system: import nixpkgs { inherit system; };
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
          # JDK, Android SDK, emulator, and scrcpy come from the host sandbox.
          default = pkgs.mkShell {
            name = "couch-tasks-android-dev";
            packages = preCommitCheck.enabledPackages;
            shellHook = preCommitCheck.shellHook;
          };
        }
      );
    };
}
