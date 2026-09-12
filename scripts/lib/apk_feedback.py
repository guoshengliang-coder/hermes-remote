"""Assert that a distributable APK really carries the in-app feedback configuration.

0.1.120 shipped without the "反馈与建议" entry. Nothing failed: the entry renders only when
`FeedbackReporter.isAvailable`, which needs BuildConfig.MISSIONGO_ENDPOINT and
MISSIONGO_SDK_TOKEN, which reach a local build through the gitignored android/missiongo.properties.
The release was built from a worktree that did not have that file, so the values compiled in empty,
the reporter became UnavailableFeedbackReporter, and the row simply was not drawn. The APK was
otherwise perfect — correct package, version, signature and hash — so every existing gate passed.

Checking the build *inputs* would not be enough: Gradle's configuration cache reads those values at
configuration time, so a stale entry can compile a different BuildConfig than the inputs describe.
This therefore checks the artifact, looking for the endpoint inside the compiled dex.

Nothing here prints the endpoint or the token. A failure says which field is missing, not its value.
"""

import sys
import zipfile


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


def main(argv):
    if len(argv) != 3:
        raise SystemExit('usage: apk_feedback.py <apk> <endpoint>')
    apk_path, endpoint = argv[1], argv[2]
    if not endpoint.strip():
        raise SystemExit(
            'This APK was built without the in-app feedback configuration, so it would ship with\n'
            'the "反馈与建议" entry silently missing (that is how 0.1.120 lost it).\n'
            'Provide android/missiongo.properties (missiongoEndpoint + missiongoSdkToken), or set\n'
            'MISSIONGO_ENDPOINT and MISSIONGO_SDK_TOKEN in the environment, and build again.'
        )
    if not dex_contains(apk_path, endpoint):
        raise SystemExit(
            'The feedback endpoint is configured but is not present in the built APK, so the\n'
            'artifact does not match its build inputs. A stale Gradle configuration-cache entry is\n'
            'the usual cause; re-run the build with --no-configuration-cache.'
        )
    print('FEEDBACK_CONFIG_OK')


if __name__ == '__main__':
    main(sys.argv)
