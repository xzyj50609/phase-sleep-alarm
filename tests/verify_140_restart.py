"""Seed synthetic app data, kill only Phase, and prove the banner survives process death."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--expected-apk-sha")
    args = parser.parse_args()
    assert args.serial.startswith("emulator-"), "Owned emulator only"
    prefix = [str(args.adb), "-s", args.serial]

    def adb(*command):
        return subprocess.check_output(prefix + list(command), timeout=90)

    assert adb("shell", "getprop", "ro.hardware").strip() in (b"ranchu", b"goldfish")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    seed = adb("shell", "am", "instrument", "-w", "-e", "class",
               "com.example.smartalarm.Phase140PersistenceSeedTest",
               "com.example.smartalarm.test/androidx.test.runner.AndroidJUnitRunner").decode("utf-8")
    assert "OK (1 test)" in seed, seed
    # The only force-stop in this script is Phase itself, never a clock package.
    adb("shell", "am", "force-stop", "com.example.smartalarm")
    stopped = subprocess.run(prefix + ["shell", "pidof", "com.example.smartalarm"], capture_output=True, timeout=30)
    assert stopped.returncode in (0, 1) and not stopped.stdout.strip(), "Phase process did not stop"
    start = adb("shell", "am", "start", "-W", "-n", "com.example.smartalarm/.MainActivity").decode("utf-8")
    assert "Status: ok" in start, start
    assert adb("shell", "pidof", "com.example.smartalarm").strip(), "Phase did not start"
    deadline = time.monotonic() + 60
    while True:
        adb("shell", "uiautomator", "dump", "/sdcard/phase140-restart.xml")
        raw = adb("exec-out", "cat", "/sdcard/phase140-restart.xml")
        texts = [node.get("text", "") for node in ET.fromstring(raw).iter("node")]
        if "躺了一个小时还没睡着，从现在重算。" in texts and "从现在重算" in texts:
            break
        assert time.monotonic() < deadline, texts
        time.sleep(1)
    (args.output_dir / "restart.xml").write_bytes(raw)
    (args.output_dir / "restart.png").write_bytes(adb("exec-out", "screencap", "-p"))
    installed_path = next(line.removeprefix("package:") for line in
        adb("shell", "pm", "path", "com.example.smartalarm").decode("utf-8").splitlines() if line.endswith("/base.apk"))
    installed_sha = adb("shell", "sha256sum", installed_path).decode("utf-8").split()[0]
    if args.expected_apk_sha:
        assert installed_sha == args.expected_apk_sha, "Restart check used a different APK"
    summary = {"seed_tests": 1, "process_restarted": True, "durable_banner_visible": True,
               "alarm_dispatch_requested": False, "serial": args.serial, "installed_apk_sha256": installed_sha}
    (args.output_dir / "restart.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
