#!/usr/bin/env python3
# -*- coding: utf-8 -*-

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

"""Tests for generate_compose.py.

    python3 -m unittest discover tools/deploy

What is worth pinning here is everything the generated file gets *silently* wrong: a volume
mounted where the image does not keep its data, a missing collation argument, a variable Compose
eats before the container sees it. None of those fail loudly -- the stack comes up and misbehaves
later, or loses the repository on restart -- so an assertion is the only thing that catches them.
The Postgres mount below is exactly such a case, and it shipped wrong because nothing asserted it.

Structural checks call build_document() and read the mapping it returns, rather than parsing the
emitted YAML: the mapping is what the emitter is given, and this repository has no YAML library to
read the output back with. The few things that exist only once written -- quoting, comments, the
doubled dollar -- are checked against the file text instead.

Standard library only, like the rest of the Python here.
"""

import contextlib
import io
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import generate_compose as gc  # noqa: E402  (needs the path set above)


def document(*argv):
    """The mapping the emitter would be handed, for one command line."""
    args = gc.parse_args(list(argv))
    return gc.build_document(args, Path('/tmp/iap-deploy-test'))


def service(doc, name):
    return doc['services'][name]


def settings(mapping):
    """A mapping without the comment entries, which are keyed '#<n>' to hold their place."""
    return {key: value for key, value in mapping.items() if not key.startswith('#')}


def environment(doc, name='iap'):
    return settings(service(doc, name).get('environment', {}))


def generate_into(directory, *argv):
    """Run the generator end to end into a directory; returns the compose file's text."""
    output = Path(directory) / 'docker-compose.yml'
    with contextlib.redirect_stdout(io.StringIO()):
        gc.main(list(argv) + ['--output', str(output)])
    return output.read_text(encoding='utf-8')


def generate(*argv):
    """Run the generator end to end in a scratch directory; returns the file's text."""
    return generate_into(tempfile.mkdtemp(), *argv)


class PostgresStorage(unittest.TestCase):
    """The Postgres service, whose two traps are both invisible until a restart."""

    def test_data_directory_is_where_postgresql_18_keeps_it(self):
        # PostgreSQL 18 moved PGDATA into a version-specific subdirectory and the image's VOLUME
        # up to the parent. Mounting the pre-18 .../data path puts the database OUTSIDE the
        # volume, with no warning and no error -- the repository just does not survive the
        # container. This assertion is the one that was missing when that shipped.
        volumes = service(document('--storage', 'postgres'), 'postgres')['volumes']
        self.assertIn('postgres-data:/var/lib/postgresql', volumes)

    def test_no_service_mounts_the_pre_18_data_path(self):
        text = generate('--storage', 'postgres')
        self.assertNotIn('/var/lib/postgresql/data', text)

    def test_initdb_forces_c_collation(self):
        # Oak orders node ids by Unicode code point. A locale collation works until the first
        # restart and then wedges the instance for good, and collation is fixed at creation time.
        initdb = settings(service(document('--storage', 'postgres'), 'postgres')['environment'])
        self.assertEqual('--encoding=UTF8 --lc-collate=C --lc-ctype=C',
                         initdb['POSTGRES_INITDB_ARGS'])

    def test_iap_waits_for_the_database_to_be_healthy(self):
        # Not service_started: the entrypoint checks the collation before launching, which needs
        # a database already accepting connections.
        depends = service(document('--storage', 'postgres'), 'iap')['depends_on']
        self.assertEqual({'condition': 'service_healthy'}, depends['postgres'])

    def test_image_comes_from_the_pins_dependabot_watches(self):
        # The version lives in images/docker-compose.yml so Dependabot can see it; if this read
        # back breaks, its pull requests would edit a file nobody consults.
        self.assertEqual(gc.image_for('postgres'),
                         service(document('--storage', 'postgres'), 'postgres')['image'])


class Storage(unittest.TestCase):

    def test_tar_adds_no_container(self):
        self.assertEqual({'iap'}, set(document()['services']))
        self.assertEqual('tar', environment(document())['OAK_STORAGE'])

    def test_each_document_store_adds_its_container_and_only_that(self):
        self.assertEqual({'iap', 'postgres'}, set(document('--storage', 'postgres')['services']))
        self.assertEqual({'iap', 'mongo'}, set(document('--storage', 'mongo')['services']))

    def test_machine_id_is_pinned_for_document_stores_only(self):
        # A container gets a new hardware address every run, so without this a quick restart
        # strands its own cluster node as permanently active.
        for storage in ('postgres', 'mongo'):
            self.assertIn('OAK_MACHINE_ID', environment(document('--storage', storage)), storage)
        self.assertNotIn('OAK_MACHINE_ID', environment(document('--storage', 'tar')))

    def test_a_volume_is_declared_for_whatever_persists(self):
        self.assertIn('postgres-data', document('--storage', 'postgres')['volumes'])
        self.assertIn('mongo-data', document('--storage', 'mongo')['volumes'])
        self.assertNotIn('postgres-data', document('--storage', 'mongo')['volumes'])


class AdditionalFeatures(unittest.TestCase):
    """--feature, which reaches the container as ADDITIONAL_SLING_FEATURES."""

    def test_absent_unless_asked_for(self):
        self.assertNotIn('ADDITIONAL_SLING_FEATURES', environment(document()))

    def test_one_coordinate_is_passed_through_verbatim(self):
        coordinate = 'mvn:io.uhndata.iap/iap-x/0.1.0-SNAPSHOT/slingosgifeature'
        self.assertEqual(coordinate,
                         environment(document('--feature', coordinate))
                         ['ADDITIONAL_SLING_FEATURES'])

    def test_several_coordinates_join_on_a_bare_comma(self):
        # docker_entry.sh reads the variable unquoted and hands it to a single -f, so a space
        # after the comma would split it into arguments the launcher never sees.
        value = environment(document('--feature', 'mvn:g/a/1/slingosgifeature',
                                     '--feature', 'mvn:g/b/2/slingosgifeature')) \
            ['ADDITIONAL_SLING_FEATURES']
        self.assertEqual('mvn:g/a/1/slingosgifeature,mvn:g/b/2/slingosgifeature', value)
        self.assertNotIn(', ', value)

    def test_a_dollar_is_doubled_so_compose_passes_it_through(self):
        # The entrypoint expands the value with ${...@P}, so a coordinate may name a
        # container-side variable -- but Compose interpolates this file first and would
        # substitute it from the host, almost certainly to nothing.
        value = environment(document('--feature', 'mvn:g/a/${PLATFORM_VERSION}/slingosgifeature'))
        self.assertEqual('mvn:g/a/$${PLATFORM_VERSION}/slingosgifeature',
                         value['ADDITIONAL_SLING_FEATURES'])

    def test_surrounding_whitespace_is_trimmed(self):
        self.assertEqual('mvn:g/a/1/slingosgifeature',
                         gc.feature_coordinate('  mvn:g/a/1/slingosgifeature  '))

    def test_values_the_entrypoint_cannot_survive_are_refused(self):
        import argparse
        for value in ('', '   ', 'mvn:a/b c/d', 'mvn:a/b,mvn:c/d'):
            with self.subTest(value=value):
                with self.assertRaises(argparse.ArgumentTypeError):
                    gc.feature_coordinate(value)

    def test_the_default_list_does_not_leak_between_parses(self):
        # `action='append'` with a mutable default is the classic argparse footgun; assert the
        # copy rather than trusting it, since a leak would only show up on a second run.
        gc.parse_args(['--feature', 'mvn:g/a/1/slingosgifeature'])
        self.assertEqual([], gc.parse_args([]).features)


class Credentials(unittest.TestCase):
    """Nothing secret belongs in docker-compose.yml, which is the file that gets shared."""

    def test_the_compose_file_stores_no_password_in_clear_text(self):
        # What CodeQL flagged on PR #161: a password literal reaching the file write. Every
        # credential is a ${...} reference into .env now, which is the reason generate_env_file
        # already gave for keeping the Keycloak client secret out of this file.
        text = generate('--storage', 'postgres', '--keycloak')
        lines = [line.strip() for line in text.splitlines()
                 if 'PASSWORD' in line and not line.lstrip().startswith('#')]
        self.assertTrue(lines, 'expected the file to set some passwords')
        for line in lines:
            self.assertRegex(line, r':\s*"\$\{[A-Z_]+\}"$', line)

    def test_iap_and_postgres_read_the_same_password_variable(self):
        # Two literals could drift apart; one variable cannot.
        doc = document('--storage', 'postgres')
        self.assertEqual('${RDB_PASSWORD}', environment(doc)['RDB_PASSWORD'])
        self.assertEqual('${RDB_PASSWORD}',
                         settings(service(doc, 'postgres')['environment'])['POSTGRES_PASSWORD'])

    def test_an_env_file_is_written_for_credentials_other_than_keycloak(self):
        with tempfile.TemporaryDirectory() as directory:
            generate_into(directory, '--storage', 'postgres')
            self.assertIn('RDB_PASSWORD=',
                          (Path(directory) / '.env').read_text(encoding='utf-8'))

    def test_no_env_file_when_nothing_needs_a_credential(self):
        with tempfile.TemporaryDirectory() as directory:
            generate_into(directory)
            self.assertFalse((Path(directory) / '.env').exists())

    def test_an_existing_env_file_is_topped_up_and_never_rewritten(self):
        # keycloak_setup.sh --write-env fills the client secret into this file, and a developer
        # may have changed a password by hand. Adding an option to a deployment that already
        # exists has to keep both, while still defining what the new services refer to.
        with tempfile.TemporaryDirectory() as directory:
            generate_into(directory, '--keycloak')
            env_file = Path(directory) / '.env'
            env_file.write_text(
                env_file.read_text(encoding='utf-8')
                .replace('KEYCLOAK_CLIENT_SECRET=', 'KEYCLOAK_CLIENT_SECRET=filled-in')
                .replace('IAP_OAUTH_ENCRYPTION_PASSWORD=devpassword',
                         'IAP_OAUTH_ENCRYPTION_PASSWORD=hand-edited'),
                encoding='utf-8')

            generate_into(directory, '--keycloak', '--storage', 'postgres')
            env = env_file.read_text(encoding='utf-8')

        self.assertIn('KEYCLOAK_CLIENT_SECRET=filled-in', env)
        self.assertIn('IAP_OAUTH_ENCRYPTION_PASSWORD=hand-edited', env)
        self.assertIn('RDB_PASSWORD=', env)
        # A second definition would be read instead of the filled-in one.
        self.assertEqual(1, env.count('KEYCLOAK_CLIENT_SECRET='))

    def test_every_variable_the_compose_file_reads_is_defined_in_the_env_file(self):
        # A ${VAR} with nothing behind it is substituted empty, and the container starts
        # misconfigured rather than failing to start.
        import re
        with tempfile.TemporaryDirectory() as directory:
            text = generate_into(directory, '--storage', 'postgres', '--keycloak', '--mail')
            env = (Path(directory) / '.env').read_text(encoding='utf-8')
        referenced = set(re.findall(r'\$\{([A-Z_]+)\}', text))
        defined = {line.split('=', 1)[0] for line in env.splitlines()
                   if '=' in line and not line.startswith('#')}
        self.assertTrue(referenced)
        self.assertEqual(set(), referenced - defined)


class Companions(unittest.TestCase):

    def test_keycloak_is_added_and_wired_both_ways(self):
        doc = document('--keycloak')
        self.assertIn('keycloak', doc['services'])
        env = environment(doc)
        # The back-channel URL is resolved inside the network, the front-channel one by the
        # browser; they differ because only the host can reach the published port.
        self.assertIn('keycloak:8080', env['BACKEND_KEYCLOAK_REALM_URL'])
        self.assertIn('localhost:{}'.format(gc.KEYCLOAK_PORT), env['FRONTEND_KEYCLOAK_REALM_URL'])

    def test_the_keycloak_secret_is_left_to_the_env_file(self):
        # A secret does not belong in a file this easy to paste into a ticket.
        env = environment(document('--keycloak'))
        self.assertTrue(env['KEYCLOAK_CLIENT_SECRET'].startswith('${'))

    def test_mail_adds_the_catcher_and_trusts_its_certificate(self):
        doc = document('--mail')
        self.assertIn(gc.MAIL_SERVICE, doc['services'])
        self.assertIn('/load_certs', ' '.join(service(doc, 'iap')['volumes']))


class Debugging(unittest.TestCase):

    def test_no_debug_publishes_no_debugger_port(self):
        self.assertEqual(['8080:8080'], service(document(), 'iap')['ports'])
        self.assertNotIn('DEBUG', environment(document()))

    def test_bare_debug_means_wait(self):
        self.assertEqual('wait', environment(document('--debug'))['DEBUG'])

    def test_the_debugger_port_is_bound_to_localhost_only(self):
        ports = service(document('--debug', 'attach'), 'iap')['ports']
        self.assertIn('127.0.0.1:5005:5005', ports)
        self.assertEqual('attach', environment(document('--debug', 'attach'))['DEBUG'])


class EmittedFile(unittest.TestCase):
    """The things that only exist once the mapping has been written out."""

    def test_comments_are_written_as_yaml_comments(self):
        self.assertIn('# Oak orders node ids', generate('--storage', 'postgres'))

    def test_the_header_records_how_to_regenerate(self):
        self.assertIn('generate_compose.py --storage mongo', generate('--storage', 'mongo'))

    def test_values_are_quoted(self):
        self.assertIn('OAK_STORAGE: "tar"', generate())

    def test_it_refuses_to_overwrite_the_image_pins(self):
        # That file is where the versions this run just read are kept, and what Dependabot
        # watches; generating over it would throw both away.
        with self.assertRaises(SystemExit):
            with contextlib.redirect_stdout(io.StringIO()):
                gc.main(['--output', str(gc.IMAGE_PINS)])


class Presentation(unittest.TestCase):

    def test_paint_is_inert_when_colour_is_off(self):
        original = gc.COLOR
        try:
            gc.COLOR = False
            self.assertEqual('plain', gc.paint('plain', 'bold', 'cyan'))
            gc.COLOR = True
            self.assertIn('plain', gc.paint('plain', 'bold'))
            self.assertTrue(gc.paint('plain', 'bold').startswith('\033['))
        finally:
            gc.COLOR = original

    def test_brief_path_picks_the_shorter_form(self):
        # The absolute path to keycloak_setup.sh is on its own wider than a terminal, which is
        # most of why the commands were unreadable; but relativising is not always shorter.
        long_absolute = Path.cwd() / 'a' / 'b'
        self.assertEqual(os.path.join('a', 'b'), gc.brief_path(long_absolute))
        self.assertEqual('/x', gc.brief_path(Path('/x')))

    def test_the_steps_name_the_commands_separately_from_the_prose(self):
        # next_steps returns data precisely so the commands can be set apart when rendered.
        actions, notes = gc.next_steps(gc.parse_args(['--keycloak']), Path('/tmp/x'))
        self.assertTrue(all(isinstance(commands, list) for _, commands in actions))
        self.assertIn('docker compose up -d keycloak', [c for _, cs in actions for c in cs])
        self.assertTrue(any('8080' in note for note in notes))

    def test_keycloak_comes_before_bringing_everything_up(self):
        # Its realm cannot be created until it is running, so the order is load-bearing.
        actions, _ = gc.next_steps(gc.parse_args(['--keycloak']), Path('/tmp/x'))
        self.assertIn('Keycloak', actions[0][0])
        self.assertEqual(2, len(actions))


if __name__ == '__main__':
    unittest.main()
