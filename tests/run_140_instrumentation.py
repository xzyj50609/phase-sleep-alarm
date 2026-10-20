"""Run only synthetic emulator tests; reject adb's misleading exit 0 on JUnit failures."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time

SAFE_CLASSES = [
    "Phase140ControllerTest", "Phase140UiTest", "ArtifactIdentityTest",
    "ControllerRegressionTest", "StandardFlowTest", "BackupRegressionTest", "RootModePermissionTest",
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--classes", default=",".join("com.example.smartalarm." + name for name in SAFE_CLASSES))
    parser.add_argument("--expected-apk-sha", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    assert args.serial.startswith("emulator-"), "Owned emulator only"
    allowed = {"com.example.smartalarm." + name for name in SAFE_CLASSES}
    assert all(name.split("#")[0] in allowed for name in args.classes.split(",")), "Only synthetic test classes are allowed"
    assert re.fullmatch(r"[0-9a-f]{64}", args.expected_apk_sha)
    base = [str(args.adb), "-s", args.serial]
    assert subprocess.check_output(base + ["shell", "getprop", "ro.hardware"]).strip() in (b"ranchu", b"goldfish")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    command = base + ["shell", "am", "instrument", "-w", "-r", "-e", "expectedApkSha", args.expected_apk_sha,
                      "-e", "class", args.classes, "com.example.smartalarm.instrumentation/androidx.test.runner.AndroidJUnitRunner"]
    started = time.monotonic()
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding="utf-8")
    lines = []
    with (args.output_dir / "instrumentation.log").open("w", encoding="utf-8") as log:
        for line in process.stdout:
            lines.append(line)
            log.write(line)
            log.flush()
            if line.startswith("INSTRUMENTATION_STATUS: test=") or "FAILURES!!!" in line or "OK (" in line:
                print(line.rstrip(), flush=True)
    return_code = process.wait()
    output = "".join(lines)
    success = re.search(r"OK \((\d+) tests?\)", output)
    bad_statuses = re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-[1-4])\s*$", output, re.MULTILINE)
    observed = re.search(r"phaseInstalledApkSha256=([0-9a-f]{64})", output)
    passed = return_code == 0 and success is not None and not bad_statuses and "FAILURES!!!" not in output
    summary = {"passed": passed, "test_count": int(success[1]) if success else None,
               "bad_statuses": bad_statuses, "adb_exit_code": return_code,
               "expected_apk_sha256": args.expected_apk_sha,
               "observed_apk_sha256": observed[1] if observed else None,
               "elapsed_seconds": round(time.monotonic() - started, 2), "classes": args.classes.split(",")}
    (args.output_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2), flush=True)
    if not passed:
        raise SystemExit("Instrumentation failed, skipped, crashed, or lacked a successful JUnit summary; inspect instrumentation.log")


if __name__ == "__main__":
    main()
