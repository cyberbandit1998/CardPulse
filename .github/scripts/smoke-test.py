#!/usr/bin/env python3
"""Starts the release APK on a running emulator and presses its way through the first thing a user does.

Code shrinking (R8) can break things that only show when the app runs, and the unit tests run against the debug build,
so this is what would notice. It installs the APK, opens the sign-in screen, types in the address of a public web
service that answers any path with JSON, and taps "Test connection". What the app says then tells what happened:

  "...doesn't look like a PokéCollector server"  the request went out over TLS, came back, and the JSON was read into
                                                 the app's own classes (Retrofit, OkHttp and kotlinx.serialization all
                                                 worked): the best result
  "...doesn't understand..." or a crash          shrinking broke something: fail
  any other message the app does not usually     something unexpected: fail, so it gets looked at
  "Can't connect...", "took too long", ...       the public service could not be reached: nothing is learned, so the
                                                 next one is tried

usage: smoke-test.py <apk> <output-dir>      (an emulator must be running and adb on the path)
Exit status 0 when the app started, did not crash, and was not debuggable; the summary says how far the check got.
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "app.cardpulse.android"
ACTIVITY = f"{PACKAGE}/.MainActivity"
# Web services that answer GET <anything>/api/health with JSON, so the app gets a reply it can read but not the right one.
ECHO_SERVERS = ["https://httpbin.org/anything", "https://httpbingo.org/anything"]
DUMP_FILE = "/data/local/tmp/cardpulse-ui.xml"

PASS_TEXT = ["look like a pok"]  # "That address answered, but it doesn't look like a PokéCollector server."
BROKEN_TEXT = ["doesn't understand", "doesn’t understand", "this app doesn"]
UNREACHABLE_TEXT = [
    "can't find that server", "can't connect", "took too long", "https) connection failed", "unreachable",
    "network error", "too many requests", "http 5", "wasn't found", "unexpected response", "permission to do that",
]
# Words on the sign-in screen that are not a message from the app, so a change on screen that only shows these is not news.
CHROME_TEXT = ["ok", "dismiss", "close"]

apk, out = Path(sys.argv[1]), Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
summary: list[str] = []
started = time.time()
screen_notes: list[str] = []  # what each failed attempt to read the screen said, shown when reading it keeps failing


def say(line: str) -> None:
    print(line, flush=True)
    summary.append(line)


def adb(*args: str, timeout: int = 120) -> subprocess.CompletedProcess:
    # The phone's log has bytes that are not text; they must not stop the check.
    return subprocess.run(["adb", *args], capture_output=True, encoding="utf-8", errors="replace", timeout=timeout)


def screenshot(name: str) -> None:
    with open(out / f"{name}.png", "wb") as handle:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=handle, timeout=60)


def note_screen(text: str) -> None:
    if text not in screen_notes[-6:]:
        screen_notes.append(text)


def dump_ui(name: str | None = None) -> ET.Element | None:
    """The screen's accessibility tree, or None when the phone was too busy to give one."""
    for _ in range(3):
        adb("shell", "rm", "-f", DUMP_FILE)
        run = adb("shell", "uiautomator", "dump", DUMP_FILE)
        text = adb("exec-out", "cat", DUMP_FILE).stdout
        if text.strip().startswith("<?xml"):
            try:
                root = ET.fromstring(text)
            except ET.ParseError as error:
                note_screen(f"the dump was not valid XML ({error})")
            else:
                if name:
                    (out / f"{name}.xml").write_text(text, encoding="utf-8")
                return root
        else:
            said = (run.stdout + " " + run.stderr).strip()[:200]
            note_screen(f"uiautomator dump: exit {run.returncode}, said {said!r}, the file held {len(text)} characters")
        time.sleep(1.5)
    return None


def texts(root: ET.Element) -> list[str]:
    found = []
    for node in root.iter("node"):
        for key in ("text", "content-desc"):
            value = (node.get(key) or "").strip()
            if value:
                found.append(value)
    return found


def centre(node: ET.Element) -> tuple[int, int]:
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "[0,0][0,0]")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(root: ET.Element, wanted: str, cls: str | None = None) -> ET.Element | None:
    for node in root.iter("node"):
        if cls and node.get("class") != cls:
            continue
        if wanted.lower() in ((node.get("text") or "") + " " + (node.get("content-desc") or "")).lower():
            return node
    return None


def tap(node: ET.Element) -> None:
    x, y = centre(node)
    adb("shell", "input", "tap", str(x), str(y))


def wait_for(wanted: list[str], seconds: int) -> ET.Element | None:
    """The screen's tree as soon as any one of the wanted texts is on it."""
    end = time.time() + seconds
    while time.time() < end:
        root = dump_ui()
        # `is not None`: an XML element with no children counts as false, and a button has none.
        if root is not None and any(find(root, text) is not None for text in wanted):
            return root
        time.sleep(1.5)
    return None


def crashed() -> str | None:
    log = adb("logcat", "-d", "-v", "brief").stdout
    (out / "logcat.txt").write_text(log, encoding="utf-8")
    for line in log.splitlines():
        if "FATAL EXCEPTION" in line or f"Process: {PACKAGE}" in line or f"ANR in {PACKAGE}" in line:
            return line.strip()
    return None


def explain_blank_screen() -> None:
    """Says what the phone knows when the sign-in screen could not be read, so the next look has something to go on."""
    say("What the phone says about the screen:")
    for line in screen_notes[-8:]:
        say(f"  {line}")
    for command in (("dumpsys", "window"), ("dumpsys", "activity", "activities")):
        for line in adb("shell", *command).stdout.splitlines():
            if any(key in line for key in ("mCurrentFocus", "mFocusedApp", "ResumedActivity")):
                say(f"  {line.strip()[:200]}")
    # Other ways to get the same tree, to learn which one this phone gives.
    compressed = adb("shell", "uiautomator", "dump", "--compressed", DUMP_FILE)
    said = (compressed.stdout + " " + compressed.stderr).strip()[:160]
    say(f"  uiautomator dump --compressed: exit {compressed.returncode}, said {said!r}")
    direct = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    say(f"  uiautomator dump to the terminal: {len(direct.stdout)} characters, begins {direct.stdout[:100]!r}")
    if direct.stdout.strip().startswith("<?xml") or "<hierarchy" in direct.stdout:
        (out / "ui-from-terminal.xml").write_text(direct.stdout, encoding="utf-8")
    root = dump_ui("ui-when-it-failed")
    if root is not None:
        say("  the screen reads as: " + " | ".join(texts(root))[:600])
        for node in list(root.iter("node"))[:40]:
            say(f"    {node.get('class')} text={node.get('text')!r} desc={node.get('content-desc')!r} {node.get('bounds')}")
    crash = crashed()
    if crash:
        say(f"  {crash}")


def finish(ok: bool) -> None:
    (out / "summary.txt").write_text("\n".join(summary) + "\n", encoding="utf-8")
    sys.exit(0 if ok else 1)


def elapsed() -> str:
    return f"{time.time() - started:.0f} s"


# --- install ----------------------------------------------------------------------------------------------------

adb("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "0")  # no on-screen keyboard over the buttons
install = adb("install", "-r", str(apk))
if "Success" not in install.stdout:
    say(f"FAIL: the APK did not install: {install.stdout.strip()} {install.stderr.strip()}")
    finish(False)
say(f"Installed {apk.name} ({apk.stat().st_size / 1e6:.1f} MB).")

flags = adb("shell", "dumpsys", "package", PACKAGE).stdout
if re.search(r"pkgFlags=\[[^\]]*\bDEBUGGABLE\b", flags):
    say("FAIL: the app is debuggable, so this is not a release build.")
    finish(False)
say("The app is not debuggable.")

# --- start ------------------------------------------------------------------------------------------------------

verdict = "inconclusive"
for server in ECHO_SERVERS:
    adb("shell", "pm", "clear", PACKAGE)
    adb("logcat", "-c")
    screen_notes.clear()
    adb("shell", "am", "start", "-W", "-n", ACTIVITY)
    # Any of the sign-in screen's own texts will do: a text field's label and a button's text are both in the tree.
    login = wait_for(["Test connection", "Server address", "Sign in"], 60)
    if login is None:
        screenshot("00-did-not-start")
        say(f"FAIL: the sign-in screen could not be read within a minute of starting the app ({elapsed()}).")
        explain_blank_screen()
        finish(False)
    screenshot("01-sign-in-screen")
    say(f"The sign-in screen appeared ({elapsed()}).")

    fields = [n for n in login.iter("node") if n.get("class") == "android.widget.EditText"]
    if not fields:
        say("FAIL: the sign-in screen has no text field.")
        explain_blank_screen()
        finish(False)
    tap(fields[0])
    time.sleep(1)
    adb("shell", "input", "text", server)
    time.sleep(1.5)
    screenshot("02-address-typed")

    before = dump_ui("ui-before-test")
    button = find(before, "Test connection") if before is not None else None
    if button is None:
        say("FAIL: the Test connection button was not found after typing the address.")
        explain_blank_screen()
        finish(False)
    if button.get("enabled") == "false":
        say("FAIL: the Test connection button stayed greyed out, so the address was not typed in.")
        explain_blank_screen()
        finish(False)
    already_there = set(texts(before))
    typed_host = server.split("//", 1)[-1].lower()  # the field may show the address in other forms; that is not a message
    tap(button)
    say(f"Tapped Test connection with {server} ({elapsed()}).")

    outcome = "waiting"
    shown = ""
    for _ in range(30):
        time.sleep(2)
        root = dump_ui()
        if root is None:
            continue
        news = [
            t for t in texts(root)
            if t not in already_there and t.strip().lower() not in CHROME_TEXT and typed_host not in t.lower()
        ]
        everything = " | ".join(news).lower()
        if any(t in everything for t in PASS_TEXT):
            outcome = "pass"
        elif any(t in everything for t in BROKEN_TEXT):
            outcome = "broken"
        elif any(t in everything for t in UNREACHABLE_TEXT):
            outcome = "unreachable"
        elif news:
            # Something new is on the screen that is not one of the messages this check knows. That is worth a look.
            outcome = "unexpected"
        if outcome != "waiting":
            shown = max(news, key=len) if news else ""
            break
    screenshot("03-after-test-connection")
    dump_ui("ui-after-test")

    crash = crashed()
    if crash:
        say(f"FAIL: the app crashed: {crash}")
        finish(False)
    if outcome == "waiting":
        say("FAIL: nothing happened a minute after tapping Test connection (the app should always say something).")
        finish(False)
    if outcome == "broken":
        say(f"FAIL: the app could not read the server's JSON: {shown}")
        finish(False)
    if outcome == "unexpected":
        say(f"FAIL: the app said something this check does not know: {shown}")
        finish(False)
    if outcome == "pass":
        say(f"The reply was read into the app's own classes: \"{shown}\" ({elapsed()}).")
        verdict = "verified"
        break
    say(f"No conclusion from {server} ({outcome}: {shown or 'no message'}); trying the next one.")

if verdict != "verified":
    say("WARNING: no public service could be reached, so reading JSON with the shrunk code was not checked this time.")

pid = adb("shell", "pidof", PACKAGE).stdout.strip()
if not pid:
    say("FAIL: the app is no longer running.")
    finish(False)
say("The app is still running and did not crash.")
say(f"RESULT: start-up and a first server call: OK; JSON read with shrunk code: {verdict}.")
finish(True)
