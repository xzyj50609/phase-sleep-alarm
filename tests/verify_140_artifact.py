"""Read-only APK checks using the installed Android SDK and Python standard library."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def normalized(name, value):
    if name == "pathData":
        return re.sub(r"\s+", "", value)
    if value.startswith("#"):
        color = value[1:].lower()
        return "#" + ("ff" + color if len(color) == 6 else color)
    if name == "strokeLineCap":
        value = {"butt": "0", "round": "1", "square": "2"}.get(value, value)
    number = re.fullmatch(r"(-?\d+(?:\.\d+)?)(dp)?", value)
    return [float(number[1]), number[2]] if number else value


def source_tree(path):
    return [(element.tag, {key.split("}")[-1]: normalized(key.split("}")[-1], value)
                           for key, value in element.attrib.items()}) for element in ET.parse(path).getroot().iter()]


def apk_tree(aapt2, apk, name):
    dump = subprocess.check_output([str(aapt2), "dump", "xmltree", str(apk), "--file", f"res/drawable/{name}"], encoding="utf-8")
    elements = []
    for line in dump.splitlines():
        element = re.search(r"E: (\w+)", line)
        if element:
            elements.append((element[1], {}))
        attribute = re.search(r"A: http://schemas.android.com/apk/res/android:(\w+)\([^)]*\)=(.*)", line)
        if attribute:
            key, value = attribute.groups()
            raw = re.search(r'\(Raw: "(.*)"\)$', value)
            value = raw[1] if raw else value.strip('"')
            elements[-1][1][key] = normalized(key, value)
    return elements


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--aapt2", type=Path, required=True)
    parser.add_argument("--icon-source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    result = {"apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(), "licenses": {}, "icons": {}}
    badging = subprocess.check_output([str(args.aapt2), "dump", "badging", str(args.apk)], encoding="utf-8")
    assert "versionCode='17'" in badging and "versionName='1.4.0'" in badging, badging.splitlines()[0]
    assert "android.permission.INTERNET" not in badging
    result["version"] = "1.4.0 (17)"
    with zipfile.ZipFile(args.apk) as archive:
        for source in [repo / "LICENSE", repo / "THIRD-PARTY-NOTICES.md", repo / "licenses/Apache-2.0.txt", repo / "licenses/libsu-LICENSE.txt"]:
            actual = archive.read("assets/licenses/" + source.name)
            assert actual == source.read_bytes(), f"License bytes differ: {source.name}"
            result["licenses"][source.name] = hashlib.sha256(actual).hexdigest()
        for name in ["ic_launcher_background.xml", "ic_launcher_foreground.xml", "ic_launcher_monochrome.xml"]:
            original = args.icon_source / name
            copied = repo / "app/src/main/res/drawable" / name
            assert original.read_text(encoding="utf-8").replace("\r\n", "\n") == copied.read_text(encoding="utf-8").replace("\r\n", "\n")
            expected = source_tree(original)
            assert expected == apk_tree(args.aapt2, args.apk, name), f"APK vector differs: {name}"
            result["icons"][name] = {"source_equals_worktree": True, "apk_vector_equals_source": True, "elements": len(expected)}
        adaptive = next(name for name in archive.namelist() if re.match(r"res/mipmap-anydpi-v\d+/ic_launcher.xml$", name))
        adaptive_dump = subprocess.check_output([str(args.aapt2), "dump", "xmltree", str(args.apk), "--file", adaptive], encoding="utf-8")
        assert "E: monochrome" in adaptive_dump
        result["adaptive_monochrome_present"] = True
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
