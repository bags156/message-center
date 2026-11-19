#!/usr/bin/env python3
"""Stream pinned, hash-checked test weights directly into the emulator, never the APK."""
import hashlib
import pathlib
import re
import shlex
import subprocess
import urllib.request

PACKAGE = "io.github.bags156.messagecenter"
source = pathlib.Path("app/src/main/java/io/github/bags156/messagecenter/QualityTranslation.java").read_text()
size = int(re.search(r"SIZE=(\d+)L", source)[1])
sha = re.search(r'SHA="([a-f0-9]{64})"', source)[1]
url = re.search(r'URL="([^"\n]+)"', source)[1]
prepared = subprocess.check_output(["adb", "shell", "am", "instrument", "-w", "-e", "quality", "prepare", PACKAGE + ".test/" + PACKAGE + ".OnDeviceSmoke"], text=True)
match = re.search(r"QUALITY_MODEL_PATH=([^\r\n]+)", prepared)
if not match:
    raise RuntimeError("Emulator model folder was not prepared: " + prepared[-600:])
path = match[1].strip()
if "/Android/data/" + PACKAGE + "/files/quality-models/" not in path or not path.endswith(".gguf"):
    raise RuntimeError("Unexpected fixture destination")
remote = "run-as " + PACKAGE + " sh -c " + shlex.quote("cat > " + shlex.quote(path))
process = subprocess.Popen(["adb", "exec-in", remote], stdin=subprocess.PIPE)
digest = hashlib.sha256()
total = last = 0
try:
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "MessageCenter-quality-check"}), timeout=60) as response:
        while chunk := response.read(4 * 1024 * 1024):
            process.stdin.write(chunk)
            digest.update(chunk)
            total += len(chunk)
            if total > size:
                raise RuntimeError("Model exceeded the pinned size")
            if total - last >= 512 * 1024 * 1024:
                print(f"Emulator model: {total * 100 // size}%", flush=True)
                last = total
    process.stdin.close()
    if process.wait(timeout=120) != 0:
        raise RuntimeError("Emulator fixture write failed")
    if total != size or digest.hexdigest() != sha:
        raise RuntimeError("Model size or SHA-256 mismatch")
    # Check the bytes Android can actually open, not just those sent to adb's stdin.
    remote_size = subprocess.check_output(["adb", "shell", "run-as", PACKAGE, "stat", "-c", "%s", path], text=True).strip()
    if remote_size != str(size):
        raise RuntimeError(f"Emulator stored {remote_size} bytes; expected {size}")
    remote_sha = subprocess.check_output(["adb", "shell", "run-as", PACKAGE, "sha256sum", path], text=True).split()[0]
    if remote_sha != sha:
        raise RuntimeError("The emulator's stored model failed SHA-256 verification")
except BaseException:
    process.terminate()
    process.wait(timeout=30)
    raise
print("QUALITY_FIXTURE_VERIFIED", flush=True)
