#!/usr/bin/env python3
"""Report on, and check, the public Android release index.

Extracted from `scripts/retire-android-releases.sh` rather than left inline, because inlining it
cost two separate bugs on the first real run:

  * `python3 -c` with an f-string: an f-string expression may not contain a backslash before
    Python 3.12, so escaping the inner quotes is a SyntaxError on the 3.9 macOS ships.
  * `python3 <<'PY'` fed by a pipe: the heredoc IS stdin, so `curl | python3 <<PY` hands the
    program to itself and the JSON never arrives.

A file has neither hazard and can be tested, which is the point.

Usage:
  release_catalog_report.py summary INDEX_JSON
  release_catalog_report.py plan    INDEX_JSON KEEP
  release_catalog_report.py verify  INDEX_JSON KEEP
"""
import json
import sys


def load(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def ordered(index):
    """Entries oldest first. versionCode is the only ordering the server guarantees."""
    return sorted(index["versions"], key=lambda v: v["versionCode"])


def summary(index):
    versions = ordered(index)
    return (f"  {len(versions)} versions, latest {index['latestVersionCode']}, "
            f"oldest {versions[0]['versionName']}, newest {versions[-1]['versionName']}")


def plan(index, keep):
    """What a retire at [keep] would do, without doing it."""
    versions = ordered(index)
    kept = versions[-keep:] if keep < len(versions) else versions
    retired = versions[:-keep] if keep < len(versions) else []
    lines = [f"  would keep {len(kept)}: {kept[0]['versionName']} .. {kept[-1]['versionName']}"]
    if retired:
        lines.append(f"  would retire {len(retired)}: "
                     f"{retired[0]['versionName']} .. {retired[-1]['versionName']}")
        lines.append("  " + ", ".join(v["versionName"] for v in retired))
    else:
        lines.append("  would retire 0 — nothing older than the newest %d" % keep)
    return "\n".join(lines)


def verify(index, keep):
    """Raise unless the index is exactly what a retire at [keep] should have left behind."""
    versions = ordered(index)
    if len(versions) != keep:
        raise SystemExit(f"expected {keep} versions, index has {len(versions)}")
    if index["latestVersionCode"] != versions[-1]["versionCode"]:
        raise SystemExit("latestVersionCode is not the newest entry")
    return summary(index)


def main(argv):
    if len(argv) < 3:
        raise SystemExit(__doc__)
    command, path = argv[1], argv[2]
    if command == "summary":
        print(summary(load(path)))
    elif command in ("plan", "verify"):
        if len(argv) < 4:
            raise SystemExit(f"{command} needs KEEP")
        keep = int(argv[3])
        if keep < 1:
            raise SystemExit("KEEP must be a positive integer")
        print(plan(load(path), keep) if command == "plan" else verify(load(path), keep))
    else:
        raise SystemExit(f"unknown command {command!r}")


if __name__ == "__main__":
    main(sys.argv)
