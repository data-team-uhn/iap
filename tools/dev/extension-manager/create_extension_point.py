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

# Scaffolds a new UI extension point in the source tree: it writes the iap:ExtensionPoint
# node definition (shipped as Sling-Initial-Content) and generates a client-side consumer
# component that renders whatever extensions plug into the point.
#
# This edits SOURCE files; it does not touch a running instance. After running it, rebuild
# and (re)deploy the owning module. To post an *extension* into a running instance without a
# rebuild, use post-extension.sh instead.
#
# Run interactively from the repository root:
#   python3 tools/dev/extension-manager/create_extension_point.py

import os
import json

# The consumer template ships next to this script, so resolve it relative to this file rather
# than the working directory.
TEMPLATE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "templates",
    "ExtensionPointUserTemplate.tsx")
EXTENSION_POINTS_CONTENT_PATH = "src/main/resources/SLING-INF/content/apps/iap/ExtensionPoints"
SLING_INITIAL_CONTENT_MARKER = "path:=/apps/iap/ExtensionPoints/"


def prompt(message):
    return input(message).strip()


def list_tsx_files(module_dir):
    # Collect the module's frontend .tsx files, so the user can pick one to wire the consumer into.
    tsx_files = []
    for basepath, _dirnames, filenames in os.walk(os.path.join(module_dir, "src")):
        for filename in filenames:
            if filename.endswith(".tsx"):
                tsx_files.append(os.path.join(basepath, filename))
    return tsx_files


def write_extension_point_json(module_dir, name, point_id, point_name):
    content_dir = os.path.join(module_dir, EXTENSION_POINTS_CONTENT_PATH)
    os.makedirs(content_dir, exist_ok=True)

    config = {
        "jcr:primaryType": "iap:ExtensionPoint",
        "iap:extensionPointId": point_id,
        "iap:extensionPointName": point_name,
    }
    json_path = os.path.join(content_dir, "{}.json".format(name))
    with open(json_path, "w") as f_json:
        f_json.write(json.dumps(config, indent=2) + "\n")
    return json_path


def pom_loads_extension_points(module_dir):
    pom_path = os.path.join(module_dir, "pom.xml")
    try:
        with open(pom_path, "r") as f_pom:
            return SLING_INITIAL_CONTENT_MARKER in f_pom.read()
    except OSError:
        return False


def write_consumer_component(name, sibling_file):
    with open(TEMPLATE_PATH, "r") as f_template:
        component = f_template.read()
    component = component.replace("_____DEFAULT_FUNCTION_NAME_____", name)
    component = component.replace("_____EXTENSION_POINT_NAME_____", name)

    component_path = os.path.join(os.path.dirname(sibling_file), "{}.tsx".format(name))
    with open(component_path, "w") as f_component:
        f_component.write(component)
    return component_path


def main():
    # Collect the identity of the extension point.
    name = prompt("Enter the name for this ExtensionPoint to be used in JCR (e.g. DashboardWidget): ")
    point_id = prompt("Enter the ID for this ExtensionPoint (e.g. iap/dashboard/widget): ")
    point_name = prompt("Enter a human-readable name for this ExtensionPoint (e.g. Dashboard widgets): ")

    # Where should the extension point live, and which frontend file should consume it?
    module_dir = prompt("Enter the path to the OSGi module that will provide this ExtensionPoint: ")

    tsx_files = list_tsx_files(module_dir)
    print("\nAvailable .tsx files in this module")
    print("-----------------------------------")
    for tsx_file in tsx_files:
        print("\t--> {}".format(tsx_file))
    sibling_file = prompt("\nEnter the .tsx file to place the generated consumer next to: ")

    # 1. The node definition, shipped as Sling-Initial-Content.
    json_path = write_extension_point_json(module_dir, name, point_id, point_name)
    print("\nWrote extension point definition: {}".format(json_path))

    # 2. Warn if the module won't actually load it into the repository.
    if not pom_loads_extension_points(module_dir):
        print("\nWARNING: {}/pom.xml doesn't look like it loads this ExtensionPoint into the JCR.".format(module_dir))
        print("Ensure its <Sling-Initial-Content> registers the '{}' content path.".format(
            EXTENSION_POINTS_CONTENT_PATH))

    # 3. A client-side consumer component to render whatever plugs into the point.
    component_path = write_consumer_component(name, sibling_file)
    print("\nGenerated a consumer component: {}".format(component_path))

    # 4. How to wire the consumer in.
    print("\nTo display this extension point in the UI:")
    print("  - Verify the import path to extensionManager in {} (adjust the relative path if needed).".format(
        component_path))
    print("  - In {}:".format(sibling_file))
    print("      add   import {} from \"./{}\";".format(name, name))
    print("      then render   <{} />".format(name))
    print("  - Register any extensions against id '{}' (e.g. with post-extension.sh).".format(point_id))


if __name__ == "__main__":
    main()
