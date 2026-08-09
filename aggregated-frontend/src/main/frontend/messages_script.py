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

# Checks that the message catalogs and the code that reads them agree.
#
# The complement of the pseudo-locale check, which proves no string reached the screen without going
# through a catalog. This proves the other direction: that every key the code asks for exists, and
# that every key a catalog defines is asked for. They are different faults with different victims —
# a missing key shows a reader a raw dotted identifier, while an unused one wastes a translator's
# time and overstates how much of the product is translated.
#
# Only the interface catalogs are checked. Content catalogs are keyed by the repository path of the
# property each entry translates, so nothing in the source tree references them by name and the
# question "is this key used" can only be answered against a running repository.

import json
import os
import re
import sys
from os import path

# Where a module keeps its catalogs: .../SLING-INF/content/libs/iap/i18n/<area>/interface/<lang>.json
CATALOG_DIR = path.join('SLING-INF', 'content', 'libs', 'iap', 'i18n')

# Directories that hold copies rather than sources. Build output would be checked against a previous
# build's catalog, and the aggregated frontend is a copy of every module's sources — including the
# tests, which this deliberately does not count.
NOT_SOURCES = ('node_modules', 'target', 'dist', 'aggregated-frontend')

# The property naming the message, on an entry node. Sling looks the message up by this, not by the
# node's own name, so this is the only string that has to match what the code asks for.
KEY_PROPERTY = 'sling:key' 

# The language the code is written in, and therefore the one that defines which keys exist. A key
# missing from a translation is untranslated, which is a different and much less urgent fault.
SOURCE_LANGUAGE = 'en'

# `const message = useMessage();` — the name is captured rather than assumed, so a file that calls it
# something else is still checked rather than silently contributing no references at all.
HOOK_BINDING = re.compile(r'(?:const|let)\s+(\w+)\s*=\s*useMessage\s*\(\s*\)')

# A key as it appears in Java, where it is passed to Messages.get/format rather than to a hook
JAVA_KEY = re.compile(r'"(iap\.[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)"')


def catalog_files(root_dir):
    """Every interface catalog in the project, as (area, language, path) triples."""
    found = []
    for root, dirs, files in os.walk(root_dir):
        dirs[:] = [d for d in dirs if not d.startswith('.') and d not in NOT_SOURCES]
        if not root.endswith('interface'):
            continue
        area = path.basename(path.dirname(root))
        if CATALOG_DIR not in root:
            continue
        for name in files:
            if name.endswith('.json'):
                found.append((area, name[:-len('.json')], path.join(root, name)))
    return found


def catalog_keys(entries):
    """The message keys a catalog defines.

    A catalog is Sling initial content: a node per message, whose own name is an identifier for
    authors and whose sling:key is what the code actually asks for. Reading the node names instead
    would compare two different vocabularies and report every message as both missing and unused.
    """
    return {entry[KEY_PROPERTY] for entry in entries.values()
            if isinstance(entry, dict) and KEY_PROPERTY in entry}


def source_files(root_dir, suffixes, maven_source='main'):
    """Every shipped source file of the given kinds, skipping tests and build output.

    Tests are skipped deliberately: a key referenced only by a test is not used by the product, and
    counting one would let a string survive in the catalog long after the screen stopped showing it.
    """
    for root, dirs, files in os.walk(root_dir):
        dirs[:] = [d for d in dirs if not d.startswith('.') and d not in NOT_SOURCES]
        if path.join('src', maven_source) not in root:
            continue
        for name in files:
            if name.endswith(suffixes):
                yield path.join(root, name)


def referenced_keys(root_dir):
    """The keys the shipped code asks for, and the calls whose key could not be read.

    A call with a computed key is reported rather than ignored. It cannot be resolved here, and left
    unmentioned it would quietly weaken both directions of this check at once: its key would look
    undefined and whatever it really asks for would look unused.
    """
    keys = set()
    unresolved = []

    for file in source_files(root_dir, ('.ts', '.tsx')):
        with open(file, 'rt', encoding='utf-8') as handle:
            text = handle.read()
        names = set(HOOK_BINDING.findall(text)) or {'message'}
        for name in names:
            for match in re.finditer(r'\b' + re.escape(name) + r'\(\s*([^)]*?)\s*\)', text):
                argument = match.group(1)
                literal = re.fullmatch(r'"([^"]*)"|\'([^\']*)\'', argument)
                if literal:
                    keys.add(literal.group(1) if literal.group(1) is not None else literal.group(2))
                elif argument:
                    line = text.count('\n', 0, match.start()) + 1
                    unresolved.append((path.relpath(file, root_dir), line, argument))

    # Java asks through Messages.get/format, where the key is one argument among several; matching the
    # key's own shape is enough, and only ever adds to what counts as used
    for file in source_files(root_dir, ('.java',)):
        with open(file, 'rt', encoding='utf-8') as handle:
            keys.update(JAVA_KEY.findall(handle.read()))

    return keys, unresolved


def report(title, entries, explanation):
    """Prints one group of faults, and says whether there were any."""
    if not entries:
        return False
    print('\n%s (%d):' % (title, len(entries)))
    print('  %s' % explanation)
    for entry in sorted(entries):
        print('    %s' % entry)
    return True


def main(args):
    # Called with the aggregated-frontend directory, exactly as webpack_script.py is, so that the two
    # build steps are invoked the same way from package.json
    root_dir = path.dirname(path.abspath(args[0]))
    catalogs = catalog_files(root_dir)
    if not catalogs:
        sys.exit('No message catalogs found under %s. Expected them at */%s/<area>/interface/<lang>.json'
                 % (root_dir, CATALOG_DIR))

    defined = {}
    translated_only = []
    for area, language, file in catalogs:
        with open(file, 'rt', encoding='utf-8') as handle:
            entries = json.load(handle)
        keys = catalog_keys(entries)
        if language == SOURCE_LANGUAGE:
            defined.update({key: path.relpath(file, root_dir) for key in keys})
        else:
            translated_only.append((area, language, path.relpath(file, root_dir), keys))

    used, unresolved = referenced_keys(root_dir)

    missing = ['%s  (asked for by the code, defined nowhere)' % key for key in used - set(defined)]
    unused = ['%s  in %s' % (key, defined[key]) for key in set(defined) - used]
    orphaned = []
    for area, language, file, keys in translated_only:
        source = {key for key, origin in defined.items() if path.dirname(origin).endswith(path.join(area, 'interface'))}
        orphaned += ['%s  in %s' % (key, file) for key in keys - source]

    faults = False
    faults |= report(
        'Messages asked for but never defined', missing,
        'A reader sees the key itself where the words should be. Add it to the ' + SOURCE_LANGUAGE + ' catalog.')
    faults |= report(
        'Messages defined but never asked for', unused,
        'Nothing shows these. Delete them, or the catalog overstates what is translated and somebody '
        'translates them again.')
    faults |= report(
        'Translated messages with nothing to translate', orphaned,
        'The source language has no such key, so these can never be shown. Usually a key that was '
        'renamed on one side only.')
    faults |= report(
        'Messages asked for by a key this check cannot read', ['%s:%s  %s' % entry for entry in unresolved],
        'A computed key defeats both halves of this check at once: it looks undefined, and whatever it '
        'really asks for looks unused. Use a literal, or move the choice into the message with a select.')

    if faults:
        sys.exit('\nMessage catalogs and code disagree. See above.')

    print('Message catalogs agree with the code: %d keys, all defined and all used.' % len(defined))


if __name__ == '__main__':
    if len(sys.argv) < 2:
        sys.exit('Usage: messages_script.py <aggregated-frontend dir>')
    main(sys.argv[1:])
