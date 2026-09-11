#!/usr/bin/env python3
"""Install a Hermes GO debug APK on physical phones, confirming known install pages by recipe.

Vendors put their own confirmation page in front of every `adb install` (HONOR: two layers;
vivo: one page with a risk checkbox), and the page changes with vendor and system updates. This
driver splits that work the way docs/DEVICE_TESTING.md describes:

  known page    -> a recipe in install-recipes.json confirms it automatically
  unknown page  -> the driver stops tapping, saves a screenshot and the UI tree, prints
                   NEEDS_ATTENTION, and KEEPS WAITING while the install stays pending. An AI
                   session (or a person) resolves the page with --inspect / --tap-text, and the
                   driver carries on from whatever page follows.
  resolved page -> if it will recur, promote it into a recipe with its UI dump as a fixture.

It never taps from hard-coded coordinates: targets are found in the `uiautomator` tree by their
text and tapped at the centre of their bounds. A blind coordinate tap once opened the HONOR
account centre and a browser. It never taps anything on a page that asks for a password, a
login, a verification code or a payment, and it only auto-confirms a page that names this app
and this version, so it cannot confirm someone else's install.

Usage:
  device-install.py [--serial S | --all] [--apk PATH] [--timeout SEC]
  device-install.py --inspect   --serial S         # what is on screen, and where
  device-install.py --tap-text TEXT --serial S     # tap one element by its text (guarded)
  device-install.py --self-test                    # every recipe matches its recorded page
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "lib"))
import apk_badging  # noqa: E402
import apk_signing  # noqa: E402

SDK = Path(os.environ.get("ANDROID_HOME") or Path.home() / "Library" / "Android" / "sdk")
ADB = str(SDK / "platform-tools" / "adb")
RECIPES = ROOT / "scripts" / "dev" / "install-recipes.json"
FIXTURES = ROOT / "scripts" / "dev" / "install-pages"
GRADLE = ROOT / "android" / "app" / "build.gradle.kts"
APK_GLOB = "android/app/build/outputs/apk/distribution/debug/Hermes-Remote-*-debug.apk"
PACKAGE = "com.hermes.remote"
EVIDENCE = Path(os.environ.get("TMPDIR") or "/tmp") / "hermes-device-install"

# Red lines. On a page carrying any of these, nothing is tapped — not by a recipe, not by
# --tap-text. Credentials and payments are for the owner to enter, never an automation.
RED_LINE = re.compile(r"密码|验证码|登录|支付|付款|password|passcode|sign[ -]?in|log[ -]?in|"
                      r"verification code|payment", re.IGNORECASE)

# While a streamed install is still uploading, the installer page has not appeared yet; waiting
# is right. Only after this long with the install pending and no installer in front is something
# else assumed to be covering it.
INSTALLER_GRACE_SEC = 25
UNKNOWN_SETTLE_SEC = 3
AFTER_TAP_SEC = 15


class Attention(Exception):
    """A page the driver will not act on by itself."""


# ---- adb --------------------------------------------------------------------------------------

def adb(serial, *args, binary=False, timeout=30):
    # stdin=DEVNULL: adb reads stdin, and a caller iterating devices through a pipe would lose
    # the rest of its input to the first adb call.
    result = subprocess.run([ADB, "-s", serial, *args], stdin=subprocess.DEVNULL,
                            capture_output=True, timeout=timeout)
    return result.stdout if binary else result.stdout.decode("utf-8", "replace")


def attached_serials():
    out = subprocess.run([str(ROOT / "scripts" / "dev" / "android-capabilities.sh"), "--serials"],
                         stdin=subprocess.DEVNULL, capture_output=True, text=True)
    return out.stdout.split()


def foreground(serial):
    out = adb(serial, "shell", "dumpsys", "activity", "activities")
    for key in ("topResumedActivity", "mResumedActivity", "ResumedActivity"):
        match = re.search(key + r"[:=]\s*ActivityRecord\{\S+ \S+ ([\w.]+)/([\w.$]+)", out)
        if match:
            return match.group(1)
    return ""


def is_installer(pkg):
    return pkg.endswith("packageinstaller")


def dump_ui(serial):
    path = "/sdcard/hermes-ui-{}.xml".format(os.getpid())
    for _ in range(3):
        # uiautomator refuses while the screen is animating ("could not get idle state").
        if "dumped" in adb(serial, "shell", "uiautomator", "dump", path).lower():
            break
        time.sleep(1)
    xml = adb(serial, "exec-out", "cat", path)
    adb(serial, "shell", "rm", "-f", path)
    return xml


def screenshot(serial, dest):
    dest.write_bytes(adb(serial, "exec-out", "screencap", "-p", binary=True))
    return dest


def tap(serial, node):
    x1, y1, x2, y2 = node["bounds"]
    adb(serial, "shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


# ---- the page ---------------------------------------------------------------------------------

def parse_nodes(xml):
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return []
    parents = {child: parent for parent in root.iter() for child in parent}
    nodes = []
    for el in root.iter("node"):
        coords = [int(v) for v in re.findall(r"-?\d+", el.get("bounds", ""))]
        if len(coords) != 4:
            continue
        nodes.append({
            "el": el,
            "text": (el.get("text") or "").strip(),
            "desc": (el.get("content-desc") or "").strip(),
            "clickable": el.get("clickable") == "true",
            "enabled": el.get("enabled") == "true",
            "checkable": el.get("checkable") == "true",
            "checked": el.get("checked") == "true",
            "bounds": tuple(coords),
        })
    by_el = {n["el"]: n for n in nodes}
    for n in nodes:
        n["parent"] = by_el.get(parents.get(n["el"]))
    return nodes


def page_text(nodes):
    return "\n".join(v for n in nodes for v in (n["text"], n["desc"]) if v)


def find_exact(nodes, label):
    hits = [n for n in nodes if label in (n["text"], n["desc"])]
    return hits


def clickable_target(node):
    """The element that actually receives the tap: the node or its nearest clickable ancestor."""
    cur = node
    while cur is not None:
        if cur["clickable"]:
            return cur
        cur = cur["parent"]
    return node


def _area(node):
    x1, y1, x2, y2 = node["bounds"]
    return max(0, x2 - x1) * max(0, y2 - y1)


def _within(inner, outer):
    return (inner[0] >= outer[0] and inner[1] >= outer[1]
            and inner[2] <= outer[2] and inner[3] <= outer[3])


def resolve_target(nodes, label):
    """(node to tap, None) for a label, or (None, reason).

    A label can appear on several nested layers of one control — vivo draws 「继续安装」 as a
    container around a smaller clickable button. Those are one control: tap its outermost
    clickable layer. Only labels on separate, non-overlapping elements are ambiguous.
    """
    hits = find_exact(nodes, label)
    if not hits:
        return None, "'{}' not on the page".format(label)
    targets, seen = [], set()
    for hit in hits:
        target = clickable_target(hit)
        if id(target) not in seen:
            seen.add(id(target))
            targets.append(target)
    outer = max(targets, key=_area)
    if not all(_within(t["bounds"], outer["bounds"]) for t in targets):
        return None, "'{}' matches {} separate elements".format(label, len(targets))
    clickable = [t for t in targets if t["clickable"]]
    return (max(clickable, key=_area) if clickable else outer), None


def usable(node):
    # vivo leaves a greyed-out button `enabled` and marks it unusable by dropping `clickable`
    # from its outer layer instead, so both have to hold.
    return node["enabled"] and node["clickable"]


def checkbox_state(nodes, label):
    """(target to tap, checked?) for a checkbox identified by its label; checked may be None."""
    hits = find_exact(nodes, label)
    if not hits:
        return None, None
    node = hits[0]
    if node["checkable"]:
        return node, node["checked"]
    # The label is often a TextView beside the box: look for a checkable sibling or cousin.
    for scope in (node["parent"], node["parent"] and node["parent"]["parent"]):
        if scope is None:
            continue
        for other in nodes:
            cur = other["parent"]
            while cur is not None and cur is not scope:
                cur = cur["parent"]
            if cur is scope and other["checkable"]:
                return other, other["checked"]
    return clickable_target(node), None


def adb_session_sizes(serial):
    """Sizes of the install sessions adb itself started and the phone still holds open.

    Page text alone cannot prove whose install is waiting: HONOR's first dialog is its own
    window, so the UI tree holds only the warning and two buttons — no app name, no version.
    The package manager's session list can: an install started by adb (initiator
    com.android.shell / uid 2000) whose size is this APK's exact byte count is ours.
    """
    out = adb(serial, "shell", "dumpsys", "package", timeout=60)
    start = out.find("Active install sessions:")
    if start < 0:
        return []
    blocks, current = [], None
    for line in out[start:].splitlines()[1:]:
        if line.strip() and not line.startswith(" "):
            break                                  # next top-level section
        if re.match(r"\s+(Active )?Session \d+:", line):
            current = []
            blocks.append(current)
        elif current is not None:
            current.append(line)
    sizes = []
    for block in blocks:
        text = " ".join(block)
        if "mDestroyed=true" in text:
            continue
        from_adb = ("installInitiatingPackageName=com.android.shell" in text
                    or "mOriginalInstallerUid=2000" in text)
        size = re.search(r"sizeBytes=(\d+)", text)
        if from_adb and size:
            sizes.append(int(size.group(1)))
    return sizes


# ---- recipes ----------------------------------------------------------------------------------

def load_recipes(path=RECIPES):
    if not path.exists():
        return []
    return json.loads(path.read_text(encoding="utf-8"))["recipes"]


def recipe_matches(recipe, fg_pkg, text, app_label, version):
    if fg_pkg not in recipe["installer"]:
        return False
    if any(required not in text for required in recipe["require_all"]):
        return False
    if recipe.get("require_app", True) and (app_label not in text or version not in text):
        return False
    return True


def next_action(recipe, nodes, done):
    """('tap', node, what) for the next step still to do, or ('wait', None, why)."""
    for index, step in enumerate(recipe["steps"]):
        key = (recipe["id"], index)
        if "check" in step:
            target, checked = checkbox_state(nodes, step["check"])
            if target is None:
                raise Attention("checkbox '{}' not on the page".format(step["check"]))
            # Unknown state (no checkable node exposed): tick exactly once, never toggle back.
            if checked or (checked is None and key in done):
                continue
            return "tap", target, "tick '{}'".format(step["check"]), key
        if "tap" in step:
            target, problem = resolve_target(nodes, step["tap"])
            if target is None:
                raise Attention(problem)
            if not usable(target):
                raise Attention("button '{}' is not usable yet".format(step["tap"]))
            return "tap", target, "press '{}'".format(step["tap"]), key
    return "wait", None, "recipe complete, waiting for the installer", None


# ---- the APK ----------------------------------------------------------------------------------

def build_tool(name):
    found = sorted((SDK / "build-tools").glob("*/" + name), reverse=True)
    if not found:
        raise SystemExit("{} not found under {}/build-tools".format(name, SDK))
    return str(found[0])


def apk_identity(apk):
    badging = subprocess.run([build_tool("aapt"), "dump", "badging", str(apk)],
                             capture_output=True, text=True).stdout
    info = apk_badging.parse(badging)
    label = re.search(r"^application-label:'([^']*)'", badging, re.MULTILINE)
    info["label"] = label.group(1) if label else ""
    signing = subprocess.run([build_tool("apksigner"), "verify", "--print-certs", str(apk)],
                             capture_output=True, text=True).stdout
    info["cert"] = apk_signing.parse(signing)
    info["size"] = Path(apk).stat().st_size
    return info


def expected_cert():
    match = re.search(r'expectedDebugCertificateSha256\s*=\s*"([0-9A-Fa-f]+)"',
                      GRADLE.read_text(encoding="utf-8"))
    if not match:
        raise SystemExit("cannot read expectedDebugCertificateSha256 from build.gradle.kts")
    return match.group(1).lower()


def default_apk():
    found = sorted(ROOT.glob(APK_GLOB), key=lambda p: p.stat().st_mtime, reverse=True)
    if not found:
        raise SystemExit("no staged APK — run: cd android && ./gradlew :app:assembleDebug")
    return found[0]


# ---- one device -------------------------------------------------------------------------------

def installed(serial):
    out = adb(serial, "shell", "dumpsys", "package", PACKAGE)
    get = lambda key: (re.search(key + r"=([^\n\r]+)", out) or [None, ""])[1].strip()
    return {"version": get("versionName"), "first": get("firstInstallTime"),
            "last": get("lastUpdateTime")}


def install(serial, apk, info, recipes, timeout):
    evidence = EVIDENCE / serial / time.strftime("%Y%m%d-%H%M%S")
    evidence.mkdir(parents=True, exist_ok=True)
    before = installed(serial)
    print("[{}] installing {} over {}".format(serial, info["versionName"], before["version"] or "nothing"))

    proc = subprocess.Popen([ADB, "-s", serial, "install", "-r", str(apk)],
                            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True)
    start = time.time()
    done, reported, step_no, taps_on_page = set(), set(), 0, {}
    first_seen, last_tap = {}, 0.0
    while proc.poll() is None:
        if time.time() - start > timeout:
            proc.kill()
            # Killing the adb client does not cancel the session on the phone: a page confirmed
            # after this point still installs (seen on vivo — the version landed 20 s after the
            # driver gave up). So report what is actually on the device, not a blanket failure.
            time.sleep(3)
            now = installed(serial)
            landed = now["version"] == info["versionName"] and now["last"] != before["last"]
            print("[{}] TIMEOUT after {}s — device now has {} ({}) — evidence in {}".format(
                serial, timeout, now["version"] or "nothing",
                "installed anyway" if landed else "not installed", evidence))
            return False
        fg = foreground(serial)
        if not is_installer(fg):
            if time.time() - start > INSTALLER_GRACE_SEC:
                sig = ("fg", fg)
                if sig not in reported:
                    reported.add(sig)
                    shot = screenshot(serial, evidence / "attention-foreground.png")
                    print("NEEDS_ATTENTION serial={} reason=install pending but '{}' is in front "
                          "of the installer evidence={}".format(serial, fg, shot))
            time.sleep(2)
            continue

        xml = dump_ui(serial)
        nodes = parse_nodes(xml)
        text = page_text(nodes)
        signature = (fg, hash(text))
        try:
            if RED_LINE.search(text):
                raise Attention("page asks for credentials or payment — never automated")
            recipe = next((r for r in recipes
                           if recipe_matches(r, fg, text, info["label"], info["versionName"])), None)
            if recipe is None:
                raise Attention("no recipe matches this page")
            kind, target, what, key = next_action(recipe, nodes, done)
            if kind == "tap":
                sizes = adb_session_sizes(serial)
                if info["size"] not in sizes:
                    raise Attention("no pending adb install of {} bytes (sessions: {}) — not "
                                    "confirming a page that may belong to another install".format(
                                        info["size"], sizes or "none"))
                taps_on_page[signature] = taps_on_page.get(signature, 0) + 1
                if taps_on_page[signature] > 3:
                    raise Attention("tapped {} three times and the page did not change".format(what))
                step_no += 1
                stem = evidence / "step-{:02d}".format(step_no)
                screenshot(serial, stem.with_suffix(".png"))
                stem.with_suffix(".xml").write_text(xml, encoding="utf-8")
                tap(serial, target)
                last_tap = time.time()
                if key is not None:
                    done.add(key)
                print("[{}] recipe {}: {}".format(serial, recipe["id"], what))
        except Attention as why:
            # Right after a confirmation the installer shows transient pages ("installing…",
            # progress) that no recipe covers. Report an unknown page only once it has held still
            # for a few seconds and the last tap is a while back; progress pages keep changing
            # their text, so they never qualify. Red-line pages are reported at once.
            now = time.time()
            first_seen.setdefault(signature, now)
            settled = now - first_seen[signature] >= UNKNOWN_SETTLE_SEC and now - last_tap >= AFTER_TAP_SEC
            if not RED_LINE.search(text) and not settled:
                time.sleep(1.2)
                continue
            if signature not in reported:
                reported.add(signature)
                name = "attention-{:02d}".format(len(reported))
                shot = screenshot(serial, evidence / (name + ".png"))
                (evidence / (name + ".xml")).write_text(xml, encoding="utf-8")
                print("NEEDS_ATTENTION serial={} reason={} foreground={} evidence={} ui={}".format(
                    serial, why, fg, shot, evidence / (name + ".xml")))
                print("  resolve with: {} --inspect --serial {}   then   --tap-text '<label>' "
                      "--serial {}".format(Path(__file__).resolve().relative_to(ROOT), serial, serial))
                print("  still waiting; the install continues as soon as the page moves on")
        time.sleep(1.2)

    output = (proc.stdout.read() if proc.stdout else "").strip()
    after = installed(serial)
    ok = "Success" in output and after["version"] == info["versionName"]
    kept = bool(before["first"]) and before["first"] == after["first"]
    print("RESULT serial={} status={} version={} data={} adb='{}' evidence={}".format(
        serial, "ok" if ok else "FAILED", after["version"] or "-",
        "kept (upgrade)" if kept else "fresh install", output.splitlines()[-1] if output else "",
        evidence))
    return ok


# ---- helpers for the AI / person resolving an unknown page ------------------------------------

def inspect(serial):
    fg = foreground(serial)
    xml = dump_ui(serial)
    nodes = parse_nodes(xml)
    out = EVIDENCE / serial
    out.mkdir(parents=True, exist_ok=True)
    shot = screenshot(serial, out / "inspect.png")
    (out / "inspect.xml").write_text(xml, encoding="utf-8")
    print("foreground: {}{}".format(fg, "   (installer)" if is_installer(fg) else ""))
    if is_installer(fg):
        # The page may not name the app (HONOR's first dialog doesn't); the session list says
        # whose install is waiting. Compare with the newest staged APK's size.
        sizes = adb_session_sizes(serial)
        staged = sorted(ROOT.glob(APK_GLOB), key=lambda p: p.stat().st_mtime, reverse=True)
        mine = staged[0].stat().st_size if staged else None
        print("pending adb installs (bytes): {}   newest staged APK: {}{}".format(
            sizes or "none", mine, "   -> ours" if mine in sizes else ""))
    if RED_LINE.search(page_text(nodes)):
        print("RED LINE: this page asks for credentials or payment — --tap-text will refuse")
    for n in nodes:
        label = n["text"] or n["desc"]
        if not label:
            continue
        target = clickable_target(n)
        flags = ("C" if target["clickable"] else "") + ("x" if not target["enabled"] else "")
        if n["checkable"]:
            flags += "☑" if n["checked"] else "☐"
        print("  [{:<3}] {}  {}".format(flags, label, n["bounds"]))
    print("screenshot: {}\nui tree:    {}".format(shot, out / "inspect.xml"))
    print("flags: C clickable, x disabled, ☐/☑ checkbox")


def tap_text(serial, label):
    nodes = parse_nodes(dump_ui(serial))
    if RED_LINE.search(page_text(nodes)):
        raise SystemExit("refusing: this page asks for credentials or payment")
    target, problem = resolve_target(nodes, label)
    if target is None:
        raise SystemExit(problem)
    if not usable(target):
        raise SystemExit("'{}' is not usable (disabled, or not clickable)".format(label))
    tap(serial, target)
    print("tapped '{}' at {} (foreground {})".format(label, target["bounds"], foreground(serial)))


# ---- self-test --------------------------------------------------------------------------------

def self_test():
    """Every recipe still matches the page it was promoted from, and acts on it as recorded."""
    recipes, failures = load_recipes(), 0
    for recipe in recipes:
        fixture = FIXTURES / recipe["fixture"]["file"]
        nodes = parse_nodes(fixture.read_text(encoding="utf-8"))
        text = page_text(nodes)
        f = recipe["fixture"]
        checks = [
            ("matches its page", recipe_matches(recipe, f["foreground"], text, f["label"], f["version"])),
            ("ignores another app's install" if recipe.get("require_app", True) else
             "page names no app — guarded at runtime by the adb install-session check",
             not recipe_matches(recipe, f["foreground"], text, "Some Other App", f["version"])
             if recipe.get("require_app", True) else True),
            ("ignores a non-installer foreground", not recipe_matches(recipe, "com.example.launcher",
                                                                      text, f["label"], f["version"])),
            ("no red-line text on its page", not RED_LINE.search(text)),
        ]
        try:
            kind, _, what, _ = next_action(recipe, nodes, set())
            checks.append(("first action is " + f["first_action"], what == f["first_action"]))
        except Attention as why:
            checks.append(("first action is " + f["first_action"] + " (got: {})".format(why), False))
        for name, ok in checks:
            failures += not ok
            print("  {} {}: {}".format("ok  " if ok else "FAIL", recipe["id"], name))
    print("{} recipe(s), {} failure(s)".format(len(recipes), failures))
    return failures == 0


# ---- main -------------------------------------------------------------------------------------

def main():
    # Line-buffer stdout. This driver is meant to run in the background while someone watches
    # for NEEDS_ATTENTION; with Python's default block buffering on a non-terminal, that line sat
    # in the buffer for the whole wait and the watcher saw nothing at all.
    sys.stdout.reconfigure(line_buffering=True)
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial", "-s")
    ap.add_argument("--all", action="store_true", help="every attached physical device")
    ap.add_argument("--apk", type=Path)
    # Generous on purpose: a known page finishes in seconds, so the timeout only ever matters while
    # a NEEDS_ATTENTION waits for someone who may be away from the machine.
    ap.add_argument("--timeout", type=int, default=900)
    ap.add_argument("--recipes", type=Path, default=RECIPES,
                    help="recipe file (an empty one reproduces a first encounter)")
    ap.add_argument("--inspect", action="store_true")
    ap.add_argument("--tap-text")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        sys.exit(0 if self_test() else 1)

    serials = attached_serials()
    if args.all:
        targets = serials
    elif args.serial:
        targets = [args.serial]
    elif os.environ.get("ANDROID_SERIAL"):
        targets = [os.environ["ANDROID_SERIAL"]]
    elif len(serials) == 1:
        targets = serials
    else:
        sys.exit("{} phones attached — pass --serial <serial> or --all".format(len(serials)))
    if not targets:
        sys.exit("no usable phone attached (./scripts/dev/android-capabilities.sh says why)")

    if args.inspect or args.tap_text:
        if len(targets) != 1:
            sys.exit("--inspect / --tap-text act on exactly one --serial")
        inspect(targets[0]) if args.inspect else tap_text(targets[0], args.tap_text)
        return

    apk = args.apk or default_apk()
    info = apk_identity(apk)
    if info["package"] != PACKAGE:
        sys.exit("{} is {}, not {}".format(apk.name, info["package"], PACKAGE))
    if info["cert"] != expected_cert():
        # A different certificate cannot upgrade the installed app, and replacing the keystore is
        # forbidden by AGENTS.md — stop here instead of letting the phone refuse half-way through.
        sys.exit("signing certificate {} is not the pinned debug certificate".format(info["cert"]))
    print("APK {}  label='{}'  version={}  certificate ok".format(apk.name, info["label"],
                                                                info["versionName"]))
    recipes = load_recipes(args.recipes)
    results = [install(s, apk, info, recipes, args.timeout) for s in targets]
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
