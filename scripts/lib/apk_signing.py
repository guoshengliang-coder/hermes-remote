"""Extract exactly one signer certificate SHA-256 from apksigner output."""

import re
import sys

DIGEST = re.compile(
    r"^(?:Signer #\d+|V\d+ Signer)\s*:?\s*certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$",
    re.MULTILINE,
)


def parse(text):
    digests = {value.lower() for value in DIGEST.findall(text)}
    if not digests:
        raise ValueError("unable to read APK signing certificate SHA-256")
    if len(digests) != 1:
        raise ValueError("APK contains more than one signing certificate SHA-256")
    return digests.pop()


def main(argv):
    if len(argv) != 1:
        raise ValueError("usage: apk_signing.py APKSIGNER_OUTPUT_FILE")
    with open(argv[0], encoding="utf-8") as stream:
        print(parse(stream.read()))


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except ValueError as error:
        raise SystemExit(str(error))
