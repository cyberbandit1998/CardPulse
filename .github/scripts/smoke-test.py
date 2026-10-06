#!/usr/bin/env python3
"""Starts the release APK on a running emulator and presses its way through the first thing a user does.

Code shrinking (R8) can break things that only show when the app runs, and the unit tests run against the debug build,
so this is what would notice. It installs the APK, opens the sign-in screen, types in the address of a public web
service that answers any path with JSON, and taps "Test connection". What the app says then tells what happened:

  "...doesn't look like a PokéCollector server"  the request went out over TLS, came back, and the JSON was read into
                                                 the app's own classes (Retrofit, OkHttp and kotlinx.serialization all
                                                 worked): the best result
  "...doesn't understand..." or a crash          shrinking broke something: fail
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

PASS_TEXT = ["look like a pok"]  # "That address answered, but it doesn't look like a PokéCollector server."
BROKEN_TEXT = ["doesn't understand", "doesn’t understand", "this app doesn"]
UNREACHABLE_TEXT = [
    "can't find that server", "can't connect", "took too long", "https) connection failed", "unreachable",
    "network error", "too many requests", "http 5", "wasn't found",
]

apk, out = Path(sys.argv[1]), Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
summary: list[str] = []


def say(line: str) -> None:
    print(line, flush=True)
    summary.append(line)


def adb(*args: str, timeout: int = 120) -> subprocess.CompletedProcess:
    return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)


def screenshot(name: str) -> None:
    with open(out / f"{name}.png", "wb") as handle:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=handle, timeout=60)


def dump_ui(name: str | None = None) -> ET.Element | None:
    """The screen's accessibility tree, or None when the phone was too busy to give one."""
    for _ in range(4):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        text = adb("exec-out", "cat", "/sdcard/ui.xml").stdout
        if text.strip().startswith("<?xml"):
            if name:
                (out / f"{name}.xml").write_text(text)
            return ET.fromstring(text)
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


def wait_for(wanted: str, seconds: int) -> ET.Element | None:
    end = time.time() + seconds
    while time.time() < end:
        root = dump_ui()
        if root is not None and find(root, wanted):
            return root
        time.sleep(1.5)
    return None


def crashed() -> str | None:
    log = adb("logcat", "-d", "-v", "brief").stdout
    (out / "logcat.txt").write_text(log)
    for line in log.splitlines():
        if "FATAL EXCEPTION" in line or f"Process: {PACKAGE}" in line or "ANR in " + PACKAGE in line:
            return line.strip()
    return None


def finish(ok: bool) -> None:
    (out / "summary.txt").write_text("\n".join(summary) + "\n")
    sys.exit(0 if ok else 1)


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
    adb("shell", "am", "start", "-W", "-n", ACTIVITY)
    # The button's own text is the surest thing to look for: a text field's label is not always in the tree.
    login = wait_for("Test connection", 60)
    if login is None:
        screenshot("00-did-not-start")
        say("FAIL: the sign-in screen did not appear within a minute of starting the app.")
        crash = crashed()
        if crash:
            say(f"      {crash}")
        finish(False)
    screenshot("01-sign-in-screen")
    say("The sign-in screen appeared.")

    fields = [n for n in login.iter("node") if n.get("class") == "android.widget.EditText"]
    if not fields:
        say("FAIL: the sign-in screen has no text field.")
        finish(False)
    tap(fields[0])
    time.sleep(1)
    adb("shell", "input", "text", server)
    time.sleep(1)
    screenshot("02-address-typed")

    root = dump_ui("ui-before-test")
    button = find(root, "Test connection") if root is not None else None
    if button is None:
        say("FAIL: the Test connection button was not found.")
        finish(False)
    tap(button)
    say(f"Tapped Test connection with {server}.")

    outcome = "waiting"
    shown = ""
    for _ in range(30):
        time.sleep(2)
        root = dump_ui()
        if root is None:
            continue
        everything = " | ".join(texts(root)).lower()
        if any(t in everything for t in PASS_TEXT):
            outcome = "pass"
        elif any(t in everything for t in BROKEN_TEXT):
            outcome = "broken"
        elif any(t in everything for t in UNREACHABLE_TEXT):
            outcome = "unreachable"
        if outcome != "waiting":
            banner = [t for t in texts(root) if any(k in t.lower() for k in PASS_TEXT + BROKEN_TEXT + UNREACHABLE_TEXT)]
            shown = banner[0] if banner else ""
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
    if outcome == "pass":
        say(f"The reply was read into the app's own classes: \"{shown}\"")
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
