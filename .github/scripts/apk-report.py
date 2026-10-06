#!/usr/bin/env python3
"""Prints what an APK is made of, so a change in its size can be seen in the build log.
usage: apk-report.py <apk>"""
import sys
import zipfile
from collections import Counter

apk = sys.argv[1]
z = zipfile.ZipFile(apk)
packed, unpacked = Counter(), Counter()
for info in z.infolist():
    name = info.filename
    if name.endswith(".dex"):
        group = "code (classes*.dex)"
    elif name.startswith("lib/"):
        group = "native libraries " + name.split("/")[1]
    elif name.startswith("res/"):
        group = "res/ (images, layouts, xml)"
    elif name == "resources.arsc":
        group = "resources.arsc"
    elif name.startswith("META-INF/"):
        group = "META-INF"
    else:
        group = "other"
    packed[group] += info.compress_size
    unpacked[group] += info.file_size
print(f"{apk}: {sum(packed.values()) / 1e6:.1f} MB of entries")
for group, size in packed.most_common():
    print(f"  {size / 1e6:6.2f} MB  (unpacked {unpacked[group] / 1e6:6.2f} MB)  {group}")
