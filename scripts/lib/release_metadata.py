"""Build the release manifest entry for an APK that already passed the package gate.

Every field comes from the gate output or the reviewed release description; nothing about the APK
is assumed here. `minSdk` in particular is the value the gate read out of the archive, so a build
that raises or lowers the minimum can never be published with stale metadata.
"""

import json
import os
import re
import sys

HEX64 = re.compile(r"^[0-9a-f]{64}$", re.IGNORECASE)


def _text(gate, field):
    value = gate.get(field)
    if not isinstance(value, str) or not value:
        raise ValueError("invalid {} in release gate output".format(field))
    return value


def _positive_int(gate, field):
    value = gate.get(field)
    if not isinstance(value, int) or isinstance(value, bool) or value < 1:
        raise ValueError("invalid {} in release gate output".format(field))
    return value


def _digest(gate, field):
    value = _text(gate, field)
    if not HEX64.match(value):
        raise ValueError("invalid {} in release gate output".format(field))
    return value.lower()


def build(gate, description, base_url, commit, published_at):
    if gate.get("gate") != "APK_RELEASE_OK":
        raise ValueError("release gate did not succeed")
    if (
        set(description) != {"channel", "releaseNotes"}
        or description.get("channel") != "internal"
        or not isinstance(description.get("releaseNotes"), list)
        or not all(isinstance(note, str) for note in description["releaseNotes"])
    ):
        raise ValueError("invalid release description")
    file_name = os.path.basename(_text(gate, "artifact"))
    return {
        "schemaVersion": 1,
        "channel": "internal",
        "versionName": _text(gate, "versionName"),
        "versionCode": _positive_int(gate, "versionCode"),
        "applicationId": "com.hermes.remote",
        "publishedAt": published_at,
        "fileName": file_name,
        "downloadUrl": base_url.rstrip("/") + "/releases/" + file_name,
        "sizeBytes": _positive_int(gate, "sizeBytes"),
        "sha256": _digest(gate, "sha256"),
        "certificateSha256": _digest(gate, "certificateSha256"),
        "minSdk": _positive_int(gate, "minSdk"),
        "releaseNotes": description["releaseNotes"],
        "sourceCommit": commit,
    }


def main(argv):
    if len(argv) != 6:
        raise ValueError(
            "usage: release_metadata.py GATE_JSON RELEASES_DIR PUBLIC_BASE_URL OUTPUT_JSON COMMIT PUBLISHED_AT"
        )
    gate_path, releases_dir, base_url, out, commit, published_at = argv
    with open(gate_path, encoding="utf-8") as stream:
        gate = json.load(stream)
    with open(os.path.join(releases_dir, _text(gate, "versionName") + ".json"), encoding="utf-8") as stream:
        description = json.load(stream)
    metadata = build(gate, description, base_url, commit, published_at)
    with open(out, "w", encoding="utf-8") as stream:
        json.dump(metadata, stream, indent=2)
        stream.write("\n")


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except ValueError as error:
        raise SystemExit(str(error))
