#!/usr/bin/env python3
"""Build the signed release APK: python3 scripts/build_release.py [--clean]"""
import argparse, os, re, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FALLBACK_JDK = "/opt/homebrew/opt/openjdk@21"
APK = ROOT / "app/build/outputs/apk/release/app-release.apk"


def jdk_ok(home):
    """True if home/bin/java is JDK 17 or 21."""
    try:
        out = subprocess.run([f"{home}/bin/java", "-version"], capture_output=True, text=True).stderr
    except OSError:
        return False
    return bool(re.search(r'version "(17|21)[."]', out))


def die(msg):
    sys.exit(f"error: {msg}")


def main():
    ap = argparse.ArgumentParser(description="Build the signed release APK.")
    ap.add_argument("--clean", action="store_true", help="run `gradlew clean` first")
    args = ap.parse_args()

    env = dict(os.environ)
    home = env.get("JAVA_HOME", "")
    if not (home and jdk_ok(home)):
        if not jdk_ok(FALLBACK_JDK):
            die(f"need JDK 17 or 21: set JAVA_HOME or install {FALLBACK_JDK}")
        env["JAVA_HOME"] = FALLBACK_JDK

    # Only key names / the keystore path are inspected; password values are never read out.
    props = Path.home() / ".gradle/gradle.properties"
    store = None
    if props.is_file():
        for line in props.read_text().splitlines():
            if line.startswith("TEXTIFY_STORE_FILE="):
                store = Path(line.split("=", 1)[1].strip()).expanduser()
    if not (store and store.is_file()):
        die("TEXTIFY_STORE_FILE missing in ~/.gradle/gradle.properties or keystore not found: APK would be unsigned")

    for tasks in ([["clean"]] if args.clean else []) + [["assembleRelease"]]:
        rc = subprocess.call(["./gradlew", *tasks], cwd=ROOT, env=env)
        if rc:
            sys.exit(rc)

    gradle = (ROOT / "app/build.gradle.kts").read_text()
    name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
    code = re.search(r"versionCode\s*=\s*(\d+)", gradle).group(1)
    print(f"APK: {APK}  ({APK.stat().st_size / 1e6:.1f} MB)  version {name} ({code})")
    print("Release and debug builds use different keys: uninstall a debug build from the phone before installing this APK.")


if __name__ == "__main__":
    main()
