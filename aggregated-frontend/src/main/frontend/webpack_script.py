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
# A directory named after a packaging layer rather than after itself — the api/impl split a Maven
# module makes — is not what its UI should be imported as: modules/submissions/impl holds the
# submissions UI, not "the impl UI". Such a directory is aggregated under its parent's name instead,
# which keeps @iap/<module> the module's own identity and keeps one module's impl from colliding
# with another's.
PACKAGING_LAYER_NAMES = {'api', 'impl'}


def namespace_for(root, dir_name):
    return path.basename(root) if dir_name in PACKAGING_LAYER_NAMES else dir_name


def merge_webpack_files(root, dir_name, namespace, aggregated_frontend_dir, webpack_config_entries):
    fl = path.join(root, dir_name, 'src', 'main', 'frontend', 'assets.config')
    if path.exists(fl):
        with open(fl, 'rt') as ins:
            lines = ins.readlines()
        # Copy lines from assets.config file
        for i in range(0, len(lines)):
            if lines[i].strip().startswith("["):
                # ensure each line ends with a comma and newline
                line = lines[i].rstrip().rstrip(',') + ',\n'
                line = line.replace("'./src/", "'./src/" + namespace + "/").replace('"./src/', '"./src/' + namespace + '/')
                webpack_config_entries.append(line)

# Copy a module's UI files from src/<maven_source>/frontend/src into the module's own
# subdirectory of the aggregated frontend, src/main/frontend/src/<module>/. Keeping each
# module in its own directory prevents files from different modules from silently
# overwriting each other, and gives cross-module imports a stable @iap/<module>/... name.
# Passing maven_source='test' merges each module's tests (authored under
# src/test/frontend/src, mirroring the src/main layout) into that same subdirectory, next
# to the sources they cover, so relative intra-module imports still resolve after
# aggregation.
def merge_ui_files(root, dir_name, namespace, aggregated_frontend_dir, maven_source='main'):
    path_to_source = path.join(root, dir_name, 'src', maven_source, 'frontend', 'src')
    if path.exists(path_to_source):
        path_to_base_source = path.join(aggregated_frontend_dir, 'src', 'main', 'frontend', 'src', namespace)
        shutil.copytree(path_to_source, path_to_base_source, dirs_exist_ok=True)


# The project-root tsconfig.json typechecks the per-module sources where they are authored,
# without aggregating them first. TypeScript resolves the packages they import by walking up
# from each source file, so it only finds the aggregated frontend's node_modules if one is
# reachable from the project root; link it there. The link is gitignored along with every
# other node_modules, and is refreshed on each build so it cannot go stale.
def link_node_modules(root_dir, aggregated_frontend_dir):
    real = path.join(aggregated_frontend_dir, 'src', 'main', 'frontend', 'node_modules')
    link = path.join(root_dir, 'node_modules')
    # Nothing to point at until `pnpm install` has run (a clean checkout builds the frontend
    # module before anything typechecks, so this resolves itself)
    if not path.isdir(real):
        return
    target = path.relpath(real, root_dir)
    if path.islink(link):
        if os.readlink(link) == target:
            return
        os.remove(link)
    elif path.exists(link):
        # A real directory here is someone's own install, not ours to replace
        return
    os.symlink(target, link, target_is_directory=True)


# Every module aggregated under src/<module>/ is importable as @iap/<module>/..., but the
# project-root tsconfig.json cannot use the aggregated tree's single "@iap/*" mapping and has
# to name each module's real source tree instead. That list is easy to forget when adding a
# module, and a missing entry only shows up as an unresolved import in whatever imports it
# later, so check it here while the authoritative set of modules is known.
def check_tsconfig_mappings(root_dir, modules):
    tsconfig = path.join(root_dir, 'tsconfig.json')
    if not path.exists(tsconfig):
        return
    with open(tsconfig, 'rt') as ins:
        contents = ins.read()
    mapped = set(re.findall(r'"@iap/([^/"]+)/\*"', contents))
    missing = sorted(set(modules) - mapped)
    if missing:
        sys.exit('Frontend module(s) %s are aggregated but have no "@iap/<module>/*" mapping in '
            'tsconfig.json. Add one pointing at each module\'s src/main/frontend/src, so that the '
            'project-root typecheck can resolve imports from them.' % ', '.join(missing))


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

    # A module's namespace — normally its directory's base name, its parent's when the directory
    # only names a packaging layer — becomes both its subdirectory in the aggregated tree and its
    # @iap/<module> import name, so it must be unique across the whole project
    seen_modules = {}
    # Of those, the ones that actually ship sources (rather than only an assets.config), which
    # are the ones the project-root tsconfig.json has to map
    source_modules = []

    for root, dirs, files in os.walk(root_dir):
        # Don't descend into hidden directories (.git, .mvnrepo, .iap-data, etc.)
        dirs[:] = [d for d in dirs if not d.startswith('.')]

        # Exclude our own directory
        if not path.samefile(root, aggregated_frontend_dir):

            for name in dirs:
                if not name == "aggregated-frontend":
                    module_dir = path.join(root, name)
                    namespace = namespace_for(root, name)
                    has_sources = path.exists(path.join(module_dir, 'src', 'main', 'frontend', 'src'))
                    if has_sources \
                            or path.exists(path.join(module_dir, 'src', 'main', 'frontend', 'assets.config')):
                        if namespace in seen_modules:
                            sys.exit('Frontend module name collision: both %s and %s would be aggregated as '
                                'src/%s/. Rename one of the module directories.'
                                % (seen_modules[namespace], module_dir, namespace))
                        seen_modules[namespace] = module_dir
                        if has_sources:
                            source_modules.append(namespace)
                    merge_webpack_files(root, name, namespace, aggregated_frontend_dir, webpack_config_entries)
                    merge_ui_files(root, name, namespace, aggregated_frontend_dir)
                    if include_tests:
                        merge_ui_files(root, name, namespace, aggregated_frontend_dir, 'test')

    check_tsconfig_mappings(root_dir, source_modules)
    link_node_modules(root_dir, aggregated_frontend_dir)

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
