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
# Both kinds of catalog are checked, by different questions, because they are keyed differently.
# Interface messages are keyed by a name a developer chose and referenced from code, so the question
# is whether the code and the catalog name the same set. Content messages are keyed by the
# repository path of the property they translate and referenced by no code at all, so the question is
# whether that property is really there and still says what the catalog thinks it says.

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

# Where a module keeps the content it ships: .../SLING-INF/content/<repository path>.json
CONTENT_DIR = path.join('SLING-INF', 'content')

# The property naming the message, on an entry node. Sling looks the message up by this, not by the
# node's own name, so this is the only string that has to match what the code asks for.
KEY_PROPERTY = 'sling:key'

# The message itself, on an entry node.
MESSAGE_PROPERTY = 'sling:message'

# The language the code is written in, and therefore the one that defines which keys exist. A key
# missing from a translation is untranslated, which is a different and much less urgent fault.
SOURCE_LANGUAGE = 'en'

# `const message = useMessage();` — the name is captured rather than assumed, so a file that calls it
# something else is still checked rather than silently contributing no references at all.
HOOK_BINDING = re.compile(r'(?:const|let)\s+(\w+)\s*=\s*useMessage\s*\(\s*\)')

# A key as it appears in Java, where it is passed to Messages.get/format rather than to a hook
JAVA_KEY = re.compile(r'"(iap\.[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)"')


def catalog_files(root_dir, kind='interface'):
    """Every catalog of one kind in the project, as (area, language, path) triples.

    Args:
        root_dir: where to look
        kind: `interface` for the developer-authored strings, `content` for translations of shipped
            content
    """
    found = []
    for root, dirs, files in os.walk(root_dir):
        dirs[:] = [d for d in dirs if not d.startswith('.') and d not in NOT_SOURCES]
        if not root.endswith(kind):
            continue
        area = path.basename(path.dirname(root))
        if CATALOG_DIR not in root:
            continue
        for name in files:
            if name.endswith('.json'):
                found.append((area, name[:-len('.json')], path.join(root, name)))
    return found


def shipped_properties(root_dir):
    """Every property of every node the project ships, by its path in the repository.

    Sling initial content is laid out as the repository is: a file at
    `SLING-INF/content/libs/iap/conf/LoginPage.json` becomes the node `/libs/iap/conf/LoginPage`, and a
    nested object inside it becomes a child of that node. Rebuilding the paths is what lets a content
    catalog -- which is keyed by them -- be checked against something rather than taken on trust.
    """
    properties = {}
    for root, dirs, files in os.walk(root_dir):
        dirs[:] = [d for d in dirs if not d.startswith('.') and d not in NOT_SOURCES]
        if CONTENT_DIR not in root or CATALOG_DIR in root:
            continue
        for name in files:
            if not name.endswith('.json'):
                continue
            file = path.join(root, name)
            base = file[file.index(CONTENT_DIR) + len(CONTENT_DIR):-len('.json')]
            try:
                with open(file, 'rt', encoding='utf-8') as handle:
                    collect_properties(json.load(handle), base.replace(os.sep, '/'), properties)
            except ValueError:
                # Not every .json under a content tree is a node definition; one that will not parse is
                # something else's business, and failing here would only report it in the wrong words
                continue
    return properties


def collect_properties(node, node_path, out):
    """Flattens one node definition into `out`, keyed by repository path."""
    if not isinstance(node, dict):
        return
    for name, value in node.items():
        if isinstance(value, dict):
            collect_properties(value, node_path + '/' + name, out)
        elif isinstance(value, str):
            out[node_path + '/' + name] = value


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


def content_entries(entries):
    """The (key, message) pairs a content catalog defines."""
    return {entry[KEY_PROPERTY]: entry.get(MESSAGE_PROPERTY, '') for entry in entries.values()
            if isinstance(entry, dict) and KEY_PROPERTY in entry}


def check_content(root_dir):
    """The three ways a content catalog stops meaning what it says.

    Nothing in the source tree references these keys, so they cannot be checked the way interface keys
    are. What can be checked is the other end: each key names a property of shipped content, and that
    property is right there in the same repository.
    """
    shipped = shipped_properties(root_dir)
    dangling = []
    shadowing = []
    undeclared = []

    for area, language, file, entries in [(a, l, f, read(f)) for a, l, f in catalog_files(root_dir, 'content')]:
        where = path.relpath(file, root_dir)
        source = content_entries(read(source_catalog(root_dir, area))) if language != SOURCE_LANGUAGE else {}
        for key, message in content_entries(entries).items():
            if key not in shipped:
                dangling.append('%s  in %s' % (key, where))
            elif language == SOURCE_LANGUAGE and message != shipped[key]:
                shadowing.append('%s  in %s' % (key, where))
            if language != SOURCE_LANGUAGE and key not in source:
                undeclared.append('%s  in %s' % (key, where))

    faults = report(
        'Translations of content that does not exist', dangling,
        'Nothing has a property at that path, so these can never be shown. Usually a property that was '
        'renamed or moved after it was translated.')
    faults |= report(
        'Translations that quietly replace the content they came from', shadowing,
        'The ' + SOURCE_LANGUAGE + ' entry no longer matches the property it repeats, and the entry is what '
        'gets rendered — so editing the content changes nothing anybody sees. Copy the property across, or '
        'edit it here instead.')
    faults |= report(
        'Content translated in one language but never declared in ' + SOURCE_LANGUAGE, undeclared,
        'These do reach a reader of that language, but an entry in the ' + SOURCE_LANGUAGE + ' catalog is '
        'what marks a property as prose, so the pseudo-locale check steps over them and any layout they '
        'break goes unnoticed.')
    return faults


def source_catalog(root_dir, area):
    """Where an area keeps the content catalog in the language it was written in."""
    for other_area, language, file in catalog_files(root_dir, 'content'):
        if other_area == area and language == SOURCE_LANGUAGE:
            return file
    return None


def read(file):
    """One catalog, or nothing where there is no such file."""
    if file is None:
        return {}
    with open(file, 'rt', encoding='utf-8') as handle:
        return json.load(handle)


def counted(number, singular, plural=None):
    """A number and its noun, agreeing."""
    return '%d %s' % (number, singular if number == 1 else plural or singular + 's')


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
        keys = catalog_keys(read(file))
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

    faults = check_content(root_dir)
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

    content = {key for _, _, file in catalog_files(root_dir, 'content') for key in content_entries(read(file))}
    print('Message catalogs agree with the code: %s, all defined and all used; %s translated, '
          'all present and unshadowed.' % (counted(len(defined), 'interface key'),
                                           counted(len(content), 'content property', 'content properties')))


if __name__ == '__main__':
    if len(sys.argv) < 2:
        sys.exit('Usage: messages_script.py <aggregated-frontend dir>')
    main(sys.argv[1:])
