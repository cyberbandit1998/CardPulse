#!/usr/bin/env python3
"""Puts a "What's new" section into a release's notes, replacing an earlier one if there is one.

usage: whats_new.py <existing-notes.md> <whats-new.md>      (prints the new notes)

The section sits between two HTML comments, which a release page doesn't show, so running this again with
different text swaps the section instead of adding a second. Without them it goes under the first paragraph
(the one-line summary), ahead of the install instructions and the checksums.
"""
import re
import sys

START, END = "<!-- whats-new -->", "<!-- /whats-new -->"

body = open(sys.argv[1], encoding="utf-8").read().strip("\n")
news = open(sys.argv[2], encoding="utf-8").read().strip()
section = f"{START}\n## What's new\n\n{news}\n{END}"

if START in body and END in body:
    body = re.sub(re.escape(START) + r".*?" + re.escape(END), lambda _: section, body, count=1, flags=re.S)
else:
    summary, _, rest = body.partition("\n\n")
    body = summary + "\n\n" + section + ("\n\n" + rest if rest else "")

print(body)
