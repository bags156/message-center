#!/usr/bin/env python3
"""Enforce the identity/version rules needed for in-place Android updates."""
import argparse
import json
import re
from pathlib import Path

PACKAGE_ID = "io.github.bags156.messagecenter"


def metadata(properties: str, certificate: str, previous=None):
    values = dict(line.strip().split("=", 1) for line in properties.splitlines()
                  if line.strip() and not line.lstrip().startswith("#"))
    version = values["versionName"]
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("versionName must have the form 0.1.0")
    code = int(values["versionCode"])
    if not 0 < code <= 2100000000:
        raise ValueError("versionCode must be a positive Android version code")
    cert = certificate.strip().lower().replace(":", "")
    if not re.fullmatch(r"[a-f0-9]{64}", cert):
        raise ValueError("The public signing certificate SHA-256 is required")
    result = dict(packageId=PACKAGE_ID, versionName=version, versionCode=code,
                  certificateSha256=cert)
    if previous:
        if previous["packageId"] != PACKAGE_ID:
            raise ValueError("Application ID changed; this would be a different app")
        if previous["certificateSha256"] != cert:
            raise ValueError("Signing certificate changed; installed apps could not update")
        if previous["versionCode"] >= code:
            raise ValueError("versionCode must increase for each release")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--previous", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    previous = json.loads(args.previous.read_text()) if args.previous else None
    result = metadata(Path("version.properties").read_text(),
                      Path("signing-certificate.sha256").read_text(), previous)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(f"Validated version {result['versionName']} (code {result['versionCode']})")
