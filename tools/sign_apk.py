#!/usr/bin/env python3
"""Decode a repository secret into temporary files without logging key material."""
import base64
import json
import os
import subprocess
import tempfile
from pathlib import Path


def main():
    secret = os.environ.get("ANDROID_SIGNING_KEY")
    if not secret:
        raise SystemExit("Configure the ANDROID_SIGNING_KEY repository secret before publishing.")
    key = json.loads(secret)
    build_tools = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "35.0.0"
    unsigned = Path("unsigned/app-release-unsigned.apk")
    out = Path("out")
    out.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory() as temp:
        private = Path(temp) / "release.pk8"
        cert = Path(temp) / "certificate.der"
        private.write_bytes(base64.b64decode(key["privateKey"], validate=True))
        cert.write_bytes(base64.b64decode(key["certificate"], validate=True))
        private.chmod(0o600)
        aligned = Path(temp) / "aligned.apk"
        subprocess.run([str(build_tools / "zipalign"), "-p", "-f", "4", str(unsigned), str(aligned)], check=True)
        target = out / "message-center.apk"
        subprocess.run([str(build_tools / "apksigner"), "sign", "--key", str(private), "--cert", str(cert),
                        "--out", str(target), str(aligned)], check=True)
        result = subprocess.check_output([str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(target)], text=True)
        import re
        actual = re.search(r"Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)", result)
        expected = Path("signing-certificate.sha256").read_text().strip().lower()
        if not actual or actual.group(1).lower() != expected:
            target.unlink(missing_ok=True)
            raise SystemExit("APK signing certificate does not match this project's permanent identity.")
        print("APK signature verified against the public certificate fingerprint.")
        manifest = subprocess.check_output([str(build_tools / "aapt"), "dump", "badging", str(target)], text=True)
        info = json.loads((out / "release-metadata.json").read_text())
        expected_parts = [f"name='{info['packageId']}'", f"versionCode='{info['versionCode']}'", f"versionName='{info['versionName']}'"]
        package = manifest.splitlines()[0]
        if not all(part in package for part in expected_parts):
            target.unlink(missing_ok=True)
            raise SystemExit("APK version/application ID does not match release metadata.")


if __name__ == "__main__":
    main()
