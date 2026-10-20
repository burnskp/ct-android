#!/usr/bin/env python3
"""Validate a release tag and derive reproducible Android versions."""
import argparse
import re
import sys


def release_version(tag):
    match = re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", tag)
    if not match:
        raise ValueError("Release tag must be vMAJOR.MINOR.PATCH without leading zeros")
    major, minor, patch = map(int, match.groups())
    if minor > 999 or patch > 999:
        raise ValueError("Release minor and patch must be between 0 and 999")
    code = major * 1_000_000 + minor * 1_000 + patch
    if not 1 <= code <= 2_100_000_000:
        raise ValueError("Release versionCode must be between 1 and 2100000000")
    return tag[1:], code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    parser.add_argument("--format", choices=("name", "github", "gradle"), default="name")
    args = parser.parse_args()
    try:
        name, code = release_version(args.tag)
    except ValueError as error:
        sys.exit(str(error))
    if args.format == "name":
        print(name)
    else:
        prefix = "-P" if args.format == "gradle" else ""
        print(f"{prefix}releaseVersionName={name}")
        print(f"{prefix}releaseVersionCode={code}")


if __name__ == "__main__":
    main()
