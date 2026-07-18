#!/usr/bin/env python
# -*- coding: utf-8 -*-

"""
   Copyright 2026 DATA @ UHN. See the NOTICE file
   distributed with this work for additional information
   regarding copyright ownership.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
"""

# Lists every UI extension point *defined in the source tree*, by finding the
# iap:ExtensionPoint node definitions that modules ship as Sling-Initial-Content under
# `.../SLING-INF/content/apps/iap/ExtensionPoints/`. For each one it prints the JSON file,
# its `iap:extensionPointId` (what extensions target) and its human-readable name.
#
# Run from the repository root:
#   python3 utils/dev/extension-manager/list_extension_points.py

import os
import json

EXTENSION_POINTS_DIR = "src/main/resources/SLING-INF/content/apps/iap/ExtensionPoints"

# Directories that never contain source and would only slow the walk down (or list build output).
SKIP_FRAGMENTS = (os.sep + "target" + os.sep, os.sep + "node_modules" + os.sep)
SKIP_PREFIXES = ("." + os.sep + ".git", "." + os.sep + ".iap-data")


def describe(json_path):
    # Return a short "id — name" description for an extension point, or a reason it couldn't be read.
    try:
        with open(json_path, "r") as node_file:
            node = json.load(node_file)
        return "{} — {}".format(
            node.get("iap:extensionPointId", "(no id)"),
            node.get("iap:extensionPointName", "(no name)"),
        )
    except (OSError, ValueError) as error:
        return "(could not parse: {})".format(error)


def main():
    found = False
    for dirpath, _dirnames, filenames in os.walk("."):
        if dirpath.startswith(SKIP_PREFIXES) or any(fragment in dirpath for fragment in SKIP_FRAGMENTS):
            continue
        if not dirpath.replace(os.sep, "/").endswith(EXTENSION_POINTS_DIR.replace(os.sep, "/")):
            continue
        for node_file in sorted(filenames):
            if not node_file.endswith(".json"):
                continue
            found = True
            json_path = os.path.join(dirpath, node_file)
            print("--> {}".format(json_path))
            print("      {}".format(describe(json_path)))

    if not found:
        print("No extension points found under any {}".format(EXTENSION_POINTS_DIR))


if __name__ == "__main__":
    main()
