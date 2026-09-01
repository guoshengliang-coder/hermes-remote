"""Read the identity an APK actually declares from `aapt dump badging` output.

The publication manifest must describe the real archive. The Android client compares the manifest
`minSdk` with the value it reads back from the downloaded APK and refuses to install on any
mismatch, so the release gate extracts it here instead of assuming a constant.
"""

import re
import sys

PACKAGE = re.compile(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'")
# Anchored per line so `targetSdkVersion:'37'` can never be read as the minimum.
MIN_SDK = re.compile(r"^sdkVersion:'([^']*)'", re.MULTILINE)


def parse(text):
    package = PACKAGE.search(text)
    if not package:
        raise ValueError("unable to read APK package metadata")
    minimum = MIN_SDK.search(text)
    if not minimum:
        raise ValueError("unable to read APK minSdk")
    if not minimum.group(1).isdigit() or int(minimum.group(1)) < 1:
        raise ValueError("invalid APK minSdk: {!r}".format(minimum.group(1)))
    name, code, version = package.groups()
    return {"package": name, "versionCode": code, "versionName": version, "minSdk": int(minimum.group(1))}


def verify(text, package, version_name, version_code):
    info = parse(text)
    if info["package"] != package:
        raise ValueError("unexpected applicationId: {}".format(info["package"]))
    if info["versionName"] != version_name or info["versionCode"] != version_code:
        raise ValueError(
            "APK version mismatch: got {}/{}, expected {}/{}".format(
                info["versionName"], info["versionCode"], version_name, version_code
            )
        )
    return info


def main(argv):
    if len(argv) != 4:
        raise ValueError("usage: apk_badging.py BADGING_FILE PACKAGE VERSION_NAME VERSION_CODE")
    badging, package, version_name, version_code = argv
    with open(badging, encoding="utf-8") as stream:
        text = stream.read()
    print(verify(text, package, version_name, version_code)["minSdk"])


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except ValueError as error:
        raise SystemExit(str(error))
