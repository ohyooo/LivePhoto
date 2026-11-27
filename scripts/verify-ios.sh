#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
  echo 'iOS verification requires an Apple Silicon Mac with Xcode and an iOS simulator.' >&2
  exit 2
fi
if ! command -v python3 >/dev/null || [[ ! -x "${JAVA_HOME:-}/bin/java" ]]; then
  echo 'Install Python 3 and point JAVA_HOME to a JDK (CI uses JDK 25).' >&2
  exit 2
fi
xcodebuild -version
xcrun --sdk iphoneos --show-sdk-path >/dev/null
xcrun --sdk iphonesimulator --show-sdk-path >/dev/null

# The stand-alone simulator commands own their boots/shutdowns. Do not choose other
# platforms (watchOS/tvOS), or mutate the user's existing simulator inventory.
select_simulator() {
  python3 -c '
import json, os, re, sys
requested = os.environ.get("IOS_SIMULATOR_UDID", "")
prefix = tuple(int(v) for v in os.environ.get("IOS_SIMULATOR_RUNTIME_PREFIX", "").split(".") if v)
candidates = []
for runtime, devices in json.load(sys.stdin).get("devices", {}).items():
    match = re.search(r"\.iOS-(\d+(?:-\d+)*)$", runtime)
    if not match:
        continue
    version = tuple(int(v) for v in match.group(1).split("-"))
    if prefix and version[:len(prefix)] != prefix:
        continue
    for device in devices:
        if device.get("isAvailable") and device.get("name", "").startswith("iPhone"):
            candidates.append((version, device.get("state") == "Shutdown", device["udid"]))
if requested:
    if any(udid == requested for _, _, udid in candidates):
        print(requested)
        sys.exit(0)
    sys.exit("IOS_SIMULATOR_UDID must identify an available iPhone simulator.")
if not candidates:
    sys.exit("Install a matching iOS runtime and add an iPhone simulator in Xcode.")
print(max(candidates)[2])
'
}
simulator="$(xcrun simctl list devices available --json | select_simulator)"
mkdir -p ci-output
# A failed rerun must not leave behind a previous success summary.
rm -f ci-output/ios-evidence.json ci-output/ios-swift-smoke.log
./gradlew -Plivephoto.ios=true --no-parallel --max-workers=1 --console=plain \
  :verifyIosBuild :iosBridge:assembleLivePhotoCoreDebugXCFramework \
  :shared:iosSimulatorArm64Test --device "$simulator" --rerun \
  :iosBridge:iosSimulatorArm64Test --device "$simulator" --rerun \
  2>&1 | tee ci-output/ios-verification.log

xcf=iosBridge/build/XCFrameworks/debug/LivePhotoCore.xcframework
python3 - "$xcf" "$simulator" <<'PY'
import collections
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

xcf = Path(sys.argv[1]).resolve()
simulator = sys.argv[2]
with (xcf / "Info.plist").open("rb") as info:
    libs = plistlib.load(info)["AvailableLibraries"]
if len(libs) != 2:
    raise SystemExit(f"Expected two iOS XCFramework slices, found {len(libs)}")
records = {}
for lib in libs:
    variant = lib.get("SupportedPlatformVariant", "device")
    if lib["SupportedPlatform"] != "ios" or lib["SupportedArchitectures"] != ["arm64"] or variant not in {"device", "simulator"}:
        raise SystemExit(f"Unexpected framework slice: {lib}")
    if variant in records:
        raise SystemExit(f"Duplicate framework slice: {variant}")
    framework = xcf / lib["LibraryIdentifier"] / lib["LibraryPath"]
    binary = framework / "LivePhotoCore"
    contents = binary.read_bytes()
    if not contents.startswith(b"!<arch>\n"):
        raise SystemExit(f"Expected a static framework archive: {binary}")
    if subprocess.check_output(["xcrun", "lipo", "-archs", str(binary)], text=True).split() != ["arm64"]:
        raise SystemExit(f"Expected arm64 code in {binary}")
    header = (framework / "Headers/LivePhotoCore.h").read_text()
    if "swift_name(\"LivePhotoInspector\")" not in header or "DefaultLivePhotoCore" in header:
        raise SystemExit("Unexpected public Swift/Objective-C bridge surface")
    sdk, triple = {
        "device": ("iphoneos", "arm64-apple-ios15.0"),
        "simulator": ("iphonesimulator", "arm64-apple-ios15.0-simulator"),
    }[variant]
    sdk_root = subprocess.check_output(["xcrun", "--sdk", sdk, "--show-sdk-path"], text=True).strip()
    subprocess.run([
        "xcrun", "--sdk", sdk, "swiftc", "-typecheck", "-sdk", sdk_root, "-target", triple,
        "-F", str(framework.parent), "iosBridge/smoke/Smoke.swift",
    ], check=True)
    records[variant] = {
        "frameworkPath": str(framework.relative_to(Path.cwd())),
        "sha256": hashlib.sha256(contents).hexdigest(),
        "size": len(contents),
    }

counts = collections.Counter()
for module, sources in (
    ("shared", "shared/src/commonTest/kotlin"),
    ("iosBridge", "iosBridge/src/iosTest/kotlin"),
):
    suites = sorted(Path(module, "build/test-results/iosSimulatorArm64Test").glob("TEST-*.xml"))
    if not suites:
        raise SystemExit(f"Missing simulator test XML for {module}")
    for xml in suites:
        suite = ET.parse(xml).getroot()
        cases = suite.findall("./testcase")
        if (
            any(int(suite.attrib[k]) != 0 for k in ("errors", "failures", "skipped"))
            or int(suite.attrib["tests"]) != len(cases)
            or any(case.find(node) is not None for case in cases for node in ("error", "failure", "skipped"))
        ):
            raise SystemExit(f"Simulator tests incomplete or failed: {xml}")
        counts[module] += len(cases)
    expected = sum(len(re.findall(r"@Test\b", src.read_text())) for src in Path(sources).rglob("*.kt"))
    if counts[module] <= 0 or counts[module] != expected:
        raise SystemExit(f"{module}: expected {expected} simulator cases, ran {counts[module]}")

log = Path("ci-output/ios-verification.log").read_text()
for module in counts:
    if not re.search(rf"^> Task :{module}:iosSimulatorArm64Test\s*$", log, re.MULTILINE):
        raise SystemExit(f"{module} simulator test task did not execute")

expected_swift_cases = [
    "ordinary-jpeg", "apple-still-only", "apple-matched-pair", "apple-mismatched-pair",
]
swift_log = Path("ci-output/ios-swift-smoke.log")


def run_smoke_command(command, timeout):
    # Retain compiler and simulator errors even on failures; do not ship media or binaries.
    with swift_log.open("a") as log:
        try:
            run = subprocess.run(
                command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                check=False, text=True, timeout=timeout,
            )
        except subprocess.TimeoutExpired as error:
            output = (error.stdout or b"").decode(errors="replace")
            log.write(output + "\nCommand timed out.\n")
            print(output, end="", flush=True)
            raise
        log.write(run.stdout)
    print(run.stdout, end="", flush=True)
    run.check_returncode()
    return run.stdout


def selected_simulator():
    devices = json.loads(subprocess.check_output(
        ["xcrun", "simctl", "list", "devices", "available", "--json"],
        text=True, timeout=30,
    ))["devices"]
    return next((runtime, device["state"]) for runtime, group in devices.items() for device in group
                if device["udid"] == simulator)


simulator_framework = Path(records["simulator"]["frameworkPath"])
with tempfile.TemporaryDirectory(prefix="livephoto-swift-smoke-") as temp:
    executable = Path(temp, "LivePhotoSwiftSmoke")
    sdk = subprocess.check_output(
        ["xcrun", "--sdk", "iphonesimulator", "--show-sdk-path"], text=True,
    ).strip()
    run_smoke_command([
        "xcrun", "--sdk", "iphonesimulator", "swiftc",
        "-sdk", sdk, "-target", "arm64-apple-ios15.0-simulator",
        "-F", str(simulator_framework.parent), "-framework", "LivePhotoCore",
        "-Xlinker", "-dead_strip", "-lc++", "iosBridge/smoke/main.swift",
        "iosBridge/smoke/SimulatorFixtures.swift", "-o", str(executable),
    ], 120)
    linked = run_smoke_command(["xcrun", "vtool", "-show-build", str(executable)], 30)
    if not re.search(r"^\s*platform\s+IOSSIMULATOR\s*$", linked, re.MULTILINE):
        raise SystemExit("Swift smoke did not link as an iOS Simulator executable")
    runtime, state_before = selected_simulator()
    try:
        swift_output = run_smoke_command([
            "xcrun", "simctl", "spawn", "--standalone", simulator, str(executable), "--",
        ], 180)
    finally:
        # On timeout/interruption, only stop a simulator this smoke started.
        if state_before == "Shutdown" and selected_simulator()[1] != "Shutdown":
            subprocess.run(["xcrun", "simctl", "shutdown", simulator], check=True, timeout=60)
    swift_executable_hash = hashlib.sha256(executable.read_bytes()).hexdigest()

swift_cases = re.findall(r"^SWIFT_IOS_CASE=([a-z-]+):PASS$", swift_output, re.MULTILINE)
if swift_cases != expected_swift_cases or swift_output.splitlines().count("SWIFT_IOS_SMOKE=SUCCESS") != 1:
    raise SystemExit(f"Incomplete Swift simulator smoke evidence: {swift_cases}")
summary = {
    "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
    "run": os.environ.get("GITHUB_RUN_ID"),
    "scope": "read-only-core-and-swift-simulator; no app, device runtime, Photos or AVFoundation claim",
    "swiftTypecheck": sorted(records),
    "swiftSimulatorSmoke": {
        "target": "arm64-apple-ios15.0-simulator",
        "runtime": runtime,
        "sdkVersion": subprocess.check_output(
            ["xcrun", "--sdk", "iphonesimulator", "--show-sdk-version"], text=True,
        ).strip(),
        "xcode": subprocess.check_output(["xcodebuild", "-version"], text=True).splitlines(),
        "cases": swift_cases,
        "executableSha256": swift_executable_hash,
    },
    "simulatorTests": dict(counts),
    "slices": records,
}
Path("ci-output/ios-evidence.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
print(json.dumps(summary, indent=2, sort_keys=True))
PY
