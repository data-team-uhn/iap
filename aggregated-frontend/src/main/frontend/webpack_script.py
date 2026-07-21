# Copyright 2026 DATA @ UHN. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import json
import re
import sys
import shutil
import os
from os import path

package_name = 'iap-aggregated-frontend'

# Collect lines of assets.config file into aggregated array.
# Entry paths are declared relative to the module's own frontend root (./src/...), and are
# rewritten to the module's dedicated subdirectory in the aggregated tree (./src/<module>/...).
def merge_webpack_files(root, dir_name, aggregated_frontend_dir, webpack_config_entries):
    fl = path.join(root, dir_name, 'src', 'main', 'frontend', 'assets.config')
    if path.exists(fl):
        with open(fl, 'rt') as ins:
            lines = ins.readlines()
        # Copy lines from assets.config file
        for i in range(0, len(lines)):
            if lines[i].strip().startswith("["):
                # ensure each line ends with a comma and newline
                line = lines[i].rstrip().rstrip(',') + ',\n'
                line = line.replace("'./src/", "'./src/" + dir_name + "/").replace('"./src/', '"./src/' + dir_name + '/')
                webpack_config_entries.append(line)

# Copy a module's UI files from src/<maven_source>/frontend/src into the module's own
# subdirectory of the aggregated frontend, src/main/frontend/src/<module>/. Keeping each
# module in its own directory prevents files from different modules from silently
# overwriting each other, and gives cross-module imports a stable @iap/<module>/... name.
# Passing maven_source='test' merges each module's tests (authored under
# src/test/frontend/src, mirroring the src/main layout) into that same subdirectory, next
# to the sources they cover, so relative intra-module imports still resolve after
# aggregation.
def merge_ui_files(root, dir_name, aggregated_frontend_dir, maven_source='main'):
    path_to_source = path.join(root, dir_name, 'src', maven_source, 'frontend', 'src')
    if path.exists(path_to_source):
        path_to_base_source = path.join(aggregated_frontend_dir, 'src', 'main', 'frontend', 'src', dir_name)
        shutil.copytree(path_to_source, path_to_base_source, dirs_exist_ok=True)


def main(args=sys.argv[1:]):
    # "aggregated-frontend" dir, resolved to an absolute path so that dirname below
    # yields the project root even when a relative path (e.g. ../../..) is passed
    aggregated_frontend_dir = path.abspath(args[0])
    # root iap project dir
    root_dir = path.dirname(aggregated_frontend_dir)

    webpack_merged_template_file = path.join(aggregated_frontend_dir, 'src', 'main', 'frontend', 'webpack.config-template.js')
    webpack_merged_file = path.join(aggregated_frontend_dir, 'src', 'main', 'frontend', 'webpack.config.js')
    shutil.copy2(webpack_merged_template_file, webpack_merged_file)
    webpack_config_entries = []

    package_merged = {}

    # Tests (src/test/frontend/src) are merged only when explicitly requested, so a
    # regular/production build (invoked by Maven without this flag) never pulls them in.
    include_tests = '--with-tests' in args

    # The module directory's base name becomes both its subdirectory in the aggregated tree
    # and its @iap/<module> import namespace, so it must be unique across the whole project
    seen_modules = {}

    for root, dirs, files in os.walk(root_dir):
        # Don't descend into hidden directories (.git, .mvnrepo, .iap-data, etc.)
        dirs[:] = [d for d in dirs if not d.startswith('.')]

        # Exclude our own directory
        if not path.samefile(root, aggregated_frontend_dir):

            for name in dirs:
                if not name == "aggregated-frontend":
                    module_dir = path.join(root, name)
                    if path.exists(path.join(module_dir, 'src', 'main', 'frontend', 'src')) \
                            or path.exists(path.join(module_dir, 'src', 'main', 'frontend', 'assets.config')):
                        if name in seen_modules:
                            sys.exit('Frontend module name collision: both %s and %s would be aggregated as '
                                'src/%s/. Rename one of the module directories.'
                                % (seen_modules[name], module_dir, name))
                        seen_modules[name] = module_dir
                    merge_webpack_files(root, name, aggregated_frontend_dir, webpack_config_entries)
                    merge_ui_files(root, name, aggregated_frontend_dir)
                    if include_tests:
                        merge_ui_files(root, name, aggregated_frontend_dir, 'test')

    # Write collected webpack config lines to the main aggregated webpack.config file
    # Remove last ',' in a last string
    webpack_config_entries[-1] = webpack_config_entries[-1].replace(',\n', '\n')

    with open(webpack_merged_file, 'r') as f:
        lines = f.readlines()
        entry_line_number = lines.index('ENTRY_CONTENT\n')
        lines[entry_line_number] = lines[entry_line_number].replace('ENTRY_CONTENT\n', '    ' + '    '.join(webpack_config_entries))

    with open(webpack_merged_file, "w") as f:
        for item in lines:
            f.write("%s" % item)

if __name__ == '__main__':
    main()
