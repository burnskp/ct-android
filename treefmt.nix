{ ... }:
{
  projectRootFile = "flake.nix";

  programs.nixpkgs-fmt.enable = true;

  settings = {
    excludes = [
      ".direnv/**"
      ".gradle/**"
      "app/build/**"
      "build/**"
    ];
  };
}
