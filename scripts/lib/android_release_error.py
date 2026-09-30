"""Shared bilingual Android release errors. Diagnostics accept only non-secret reason identifiers."""
import json
from pathlib import Path
import sys


DEFINITIONS = json.loads(Path(__file__).with_name("android-release-errors.json").read_text(encoding="utf-8"))
SAFE_REASONS = frozenset({
    "check_failed", "invalid_distribution_mode", "gradle_configuration_check_failed",
    "build_config_missing", "build_config_field_missing", "build_config_literal_invalid",
    "invalid_arguments", "feedback_value_missing", "apk_unreadable",
    "feedback_value_absent_from_apk",
})


def release_error(kind, reason="check_failed"):
    error = dict(DEFINITIONS[kind])
    error["technicalCause"] = reason if reason in SAFE_REASONS else "redacted"
    return error


def report_error(kind, reason="check_failed"):
    error = release_error(kind, reason)
    print(f'{error["code"]}: {error["summaryZh"]}\n{error["summaryEn"]}', file=sys.stderr)
    print(json.dumps(error, ensure_ascii=False), file=sys.stderr)


if __name__ == "__main__":
    report_error(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "check_failed")
