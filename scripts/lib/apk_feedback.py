"""Assert that a distributable APK really carries the in-app feedback configuration.

0.1.120 shipped without the "反馈与建议" entry. Nothing failed: the entry renders only when
`FeedbackReporter.isAvailable`, which needs BuildConfig.MISSIONGO_ENDPOINT and
MISSIONGO_SDK_TOKEN, which reach a local build through the gitignored android/missiongo.properties.
The release was built from a worktree that did not have that file, so the values compiled in empty,
the reporter became UnavailableFeedbackReporter, and the row simply was not drawn. The APK was
otherwise perfect — correct package, version, signature and hash — so every existing gate passed.

Checking the build *inputs* would not be enough: Gradle's configuration cache reads those values at
configuration time, so a stale entry can compile a different BuildConfig than the inputs describe.
This therefore reads the generated BuildConfig that fed the compilation and checks that both values
are nonempty and present inside the compiled dex.

Nothing here prints the endpoint or the token. A failure says which field is missing, not its value.
"""

import json
import re
import sys
import zipfile


FIELDS = ("MISSIONGO_ENDPOINT", "MISSIONGO_SDK_TOKEN")


def dex_contains(apk_path, needle):
    """True when any classes*.dex in the APK contains needle (as UTF-8 bytes).

    Dex string data is MUTF-8, which is byte-identical to UTF-8 for anything without NUL or
    non-BMP characters — both absent from a URL — so a plain substring search is exact here.
    """
    probe = needle.encode('utf-8')
    with zipfile.ZipFile(apk_path) as archive:
        for entry in archive.namelist():
            if not (entry.startswith('classes') and entry.endswith('.dex')):
                continue
            if probe in archive.read(entry):
                return True
    return False


def read_build_config(path):
    try:
        with open(path, encoding="utf-8") as stream:
            source = stream.read()
    except OSError:
        raise SystemExit("The generated BuildConfig is missing; rebuild the APK before packaging.")
    values = {}
    for field in FIELDS:
        match = re.search(
            rf'^\s*public static final String {field} = "((?:\\.|[^"\\])*)";\s*$',
            source,
            re.MULTILINE,
        )
        if not match:
            raise SystemExit(f"The generated BuildConfig is missing {field}.")
        try:
            values[field] = json.loads(f'"{match.group(1)}"')
        except json.JSONDecodeError:
            raise SystemExit(f"The generated BuildConfig contains an invalid {field} literal.")
    return values


def main(argv):
    if len(argv) != 3:
        raise SystemExit('usage: apk_feedback.py <apk> <generated-build-config>')
    apk_path, build_config_path = argv[1], argv[2]
    values = read_build_config(build_config_path)
    for field, value in values.items():
        if not value.strip():
            raise SystemExit(
                f'This APK was built without {field}, so the in-app feedback entry would be\n'
                'silently missing or unusable (that is how 0.1.120 lost it). Configure both\n'
                'MissionGo settings and build again.'
            )
        if not dex_contains(apk_path, value):
            raise SystemExit(
                f'{field} is nonempty in the generated BuildConfig but is not present in the APK.\n'
                'The artifact does not match its generated source; rebuild with\n'
                '--no-configuration-cache.'
            )
    print('FEEDBACK_CONFIG_OK')


if __name__ == '__main__':
    main(sys.argv)
