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

"""Writes a Docker Compose file running IAP together with the services it talks to.

Every option here chooses a *companion container* -- a repository back-end, an identity provider,
a mail server -- and wires the IAP container to it. There is deliberately no option for turning
individual IAP modules on or off: the `core` distribution the image ships already contains all of
them, so what a deployment actually varies is what surrounds it.

Only the standard library is used, like the rest of the Python in this repository; the generated
file is plain YAML, commented, meant to be read and edited afterwards.
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

# The companion images are pinned in images/docker-compose.yml rather than here, so that
# Dependabot can watch them: it reads image versions out of Compose files and Dockerfiles, and
# would never find them in a Python constant. Reading them back means its pull requests change
# what is actually deployed instead of only editing a file nobody consults.
IMAGE_PINS = HERE / 'images' / 'docker-compose.yml'

# The `oak_persistence_rdb` feature already defaults to these, but a generated file should say out
# loud what it connects to rather than leaning on a default defined three modules away.
RDB_DATABASE = "iap"
RDB_USER = "iap"
RDB_PASSWORD = "iap"

# The database name `oak_persistence_mongods` defaults to.
MONGO_DATABASE = "sling"

# The host port Keycloak is published on, matching tools/dev/keycloak/ and docs/keycloak-oidc.md.
KEYCLOAK_PORT = 8084
KEYCLOAK_REALM = "iap"

# docker_entry.sh hardwires this hostname when SMTPS_LOCAL_TEST_CONTAINER is set, so the mail
# service has to be called exactly this.
MAIL_SERVICE = "smtps_test_container"

# Oak reclaims a cluster node only when the hardware address still matches, and a container is
# given a fresh one every run. Everything generated here is a single IAP instance, so pinning the
# address is safe and saves the reader from the restart wedge described in docs/docker.md.
OAK_MACHINE_ID = "ca2d50000001"


def feature_coordinate(value):
    """Check one --feature value for the two things that break the entrypoint outright.

    The format itself is deliberately not policed: the launcher's -f takes Maven coordinates,
    file paths and URLs, and a pass-through option that second-guesses it would block a
    legitimate value later. What is rejected here is only what cannot survive the trip --
    docker_entry.sh tests the variable unquoted, so whitespace turns into `[: too many
    arguments`, and the coordinates are joined with commas, so an embedded comma would read as a
    separator.
    """
    coordinate = value.strip()
    if not coordinate:
        raise argparse.ArgumentTypeError("a feature coordinate cannot be empty")
    if any(character.isspace() for character in coordinate):
        raise argparse.ArgumentTypeError(
            "feature coordinates cannot contain whitespace, and the container's entrypoint reads "
            "them unquoted: {!r}".format(value))
    if ',' in coordinate:
        raise argparse.ArgumentTypeError(
            "commas separate coordinates, so one cannot contain a comma; pass --feature again "
            "instead: {!r}".format(value))
    return coordinate


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Generate a Docker Compose file for IAP and the services it talks to.",
        epilog="Each option adds a container and wires IAP to it; see tools/deploy/README.md. "
               "IAP modules are not individually selectable, because the image already has "
               "all of them.")

    parser.add_argument('--storage', choices=['tar', 'postgres', 'mongo'], default='tar',
                        help="Where the repository lives: the container filesystem (tar, the "
                             "default, adds no container), a PostgreSQL container, or a MongoDB "
                             "container")
    parser.add_argument('--keycloak', action='store_true',
                        help="Add a Keycloak container and point IAP's OIDC sign-in at it")
    parser.add_argument('--mail', action='store_true',
                        help="Add an SMTPS server that writes every message it receives to a file "
                             "under ./mail instead of delivering it")

    parser.add_argument('--image', default='iap/iap',
                        help="The IAP Docker image to run [default: iap/iap]")
    parser.add_argument('--port', type=int, default=8080,
                        help="Host port to publish IAP on [default: 8080]")
    parser.add_argument('--dev', action='store_true',
                        help="Mount ~/.m2 into the container, which the developer flavour of the "
                             "image needs in order to resolve third-party artifacts")
    parser.add_argument('--debug', nargs='?', choices=['wait', 'attach'], const='wait',
                        help="Publish the JDWP debugger on 127.0.0.1:5005. `wait` (the default "
                             "when the flag is given alone) holds the JVM until a debugger "
                             "attaches, for debugging startup itself; `attach` starts normally "
                             "and lets a debugger connect whenever it likes")
    parser.add_argument('--feature', dest='features', action='append', metavar='COORD',
                        type=feature_coordinate, default=[],
                        help="A feature to start on top of the distribution the image already "
                             "carries, e.g. "
                             "mvn:io.uhndata.iap/iap-something/0.1.0-SNAPSHOT/slingosgifeature. "
                             "Repeatable; the coordinates reach the container as the "
                             "comma-separated ADDITIONAL_SLING_FEATURES it expects")
    parser.add_argument('--output', default='docker-compose.yml',
                        help="Where to write the generated file [default: docker-compose.yml]")

    return parser.parse_args(argv)


### Terminal presentation
#
# The point of all of this is that the few lines the reader has to *act on* -- the commands --
# should be findable at a glance in what is otherwise a page of prose. Colour is decoration; the
# markers, the indentation and the rules are what carry the structure, which is why the plain
# output has to stand on its own.

# Whether to emit ANSI at all. Colour goes to a terminal and nowhere else, or it ends up as
# escape codes in a log, a pipe or a CI transcript. NO_COLOR/FORCE_COLOR are the cross-tool
# conventions (no-color.org): NO_COLOR wins, and any value counts, including empty.
if 'NO_COLOR' in os.environ:
    COLOR = False
elif os.environ.get('FORCE_COLOR'):
    COLOR = True
else:
    COLOR = sys.stdout.isatty() and os.environ.get('TERM') != 'dumb'

# A box-drawing character crashes the script outright on a stdout that cannot encode it -- an
# ASCII locale with no PEP 538 coercion, which minimal containers still manage -- so the glyphs
# degrade instead of raising.
try:
    '─┈✓·'.encode(sys.stdout.encoding or 'ascii')
    GLYPH = {'rule': '─', 'soft': '┈', 'tick': '✓', 'dot': '·'}
except (UnicodeEncodeError, LookupError):
    GLYPH = {'rule': '-', 'soft': '-', 'tick': '*', 'dot': '-'}

# Width for the rules. Terminal width when there is one, and get_terminal_size falls back to 80
# off a terminal; capped so a maximised window does not draw a 300-column line.
WIDTH = min(shutil.get_terminal_size().columns, 78)

ANSI = {
    'bold': '1', 'dim': '2', 'green': '32',
    'yellow': '33', 'blue': '34', 'cyan': '96', 'reset': '0',
}


def paint(text, *styles):
    """Wrap text in ANSI styles, or return it untouched when colour is off."""
    if not COLOR or not styles:
        return text
    codes = ';'.join(ANSI[style] for style in styles)
    return "\033[{}m{}\033[{}m".format(codes, text, ANSI['reset'])


def say_wrote(what, detail=''):
    """Report something this run created. One line, ticked, so the writes read as a group."""
    print("{} {}{}".format(paint(GLYPH['tick'], 'green'), what,
                           paint(' ' + detail, 'dim') if detail else ''))


def say_kept(what):
    """Report something this run deliberately left alone -- not a write, and not a failure."""
    print("{} {} {}".format(paint(GLYPH['dot'], 'dim'), what, paint('(kept)', 'dim', 'yellow')))


def heading(title):
    """A titled horizontal rule, opening a block."""
    bar = GLYPH['rule'] * max(WIDTH - len(title) - 3, 3)
    print("\n{} {}".format(paint(title.upper(), 'bold', 'blue'), paint(bar, 'dim')))


def command(text):
    """The lines the reader actually has to run: prompt marker, indented, and the only cyan."""
    print("     {} {}".format(paint('$', 'dim', 'green'), paint(text, 'bold', 'cyan')))


def brief_path(path):
    """The shorter of the absolute path and one relative to the working directory.

    A command is only readable if it fits on a line, and these paths are the reason they did not:
    the absolute path to keycloak_setup.sh inside a worktree is on its own wider than a terminal.
    Relative is usually far shorter, but not when it climbs out through a chain of `..`, hence
    picking rather than always relativising.
    """
    absolute = str(path)
    try:
        relative = os.path.relpath(str(path))
    except ValueError:
        # Different drive on Windows; there is no relative form.
        return absolute
    return relative if len(relative) < len(absolute) else absolute

### Reading the pinned image versions

def image_for(service):
    """The image pinned for one service in images/docker-compose.yml.

    Deliberately not a YAML parse: the file has one shape, this repository ships no YAML library,
    and Dependabot edits only the tag inside an `image:` line, leaving everything around it alone.
    """
    if not hasattr(image_for, 'pins'):
        try:
            text = IMAGE_PINS.read_text(encoding='utf-8')
        except OSError as error:
            sys.exit("ERROR: cannot read the pinned image versions from {}: {}".format(
                IMAGE_PINS, error))

        pins = {}
        current = None
        for line in text.splitlines():
            if line.startswith('#') or not line.strip():
                continue
            service_match = re.match(r'^  ([A-Za-z0-9._-]+):\s*$', line)
            if service_match:
                current = service_match.group(1)
                continue
            image_match = re.match(r'^\s+image:\s*"?([^"\s]+)"?\s*$', line)
            if image_match and current:
                pins[current] = image_match.group(1)
        image_for.pins = pins

    if service not in image_for.pins:
        sys.exit("ERROR: no image pinned for '{}' in {}. Every service the generator can "
                 "produce needs one there, so that Dependabot keeps it up to date.".format(
                     service, IMAGE_PINS))
    return image_for.pins[service]


### Emitting YAML
#
# Only what this script builds has to be representable: nested mappings, lists of scalars, empty
# entries, and comment lines. That is little enough to write out directly, which keeps the script
# free of third-party dependencies -- the rest of the Python in this repository has none either --
# and lets the output carry the comments explaining it, which a YAML library would drop.

def comment(mapping, text):
    """Add a comment line to a mapping, keyed so that it keeps its place among the real keys."""
    mapping["#{}".format(len(mapping))] = text


def scalar(value):
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    # Quoting everything else is never wrong, and saves picking out the values that would
    # otherwise read as numbers, booleans, or -- with a colon in them -- nested mappings.
    return '"{}"'.format(str(value).replace('\\', '\\\\').replace('"', '\\"'))


def emit(node, indent, out):
    pad = '  ' * indent
    if isinstance(node, dict):
        for key, value in node.items():
            if key.startswith('#'):
                out.append("{}# {}".format(pad, value))
            elif value is None:
                # A bare key: how Compose is told to create a volume or network with its defaults.
                out.append("{}{}:".format(pad, key))
            elif isinstance(value, (dict, list)):
                out.append("{}{}:".format(pad, key))
                emit(value, indent + 1, out)
            else:
                out.append("{}{}: {}".format(pad, key, scalar(value)))
    elif isinstance(node, list):
        for item in node:
            out.append("{}- {}".format(pad, scalar(item)))


def dump(document, header):
    lines = ["# {}".format(line).rstrip() for line in header]
    lines.append("")
    emit(document, 0, lines)
    lines.append("")
    return "\n".join(lines)


### Files generated alongside the Compose file

def generate_mail_certificate(ssl_directory):
    """Create the certificate the mail server presents, and IAP has to trust.

    IAP reaches a mail server over SMTPS -- TLS from the first byte, since Sling's mail service
    speaks the `smtps` protocol -- so even a throwaway server needs one. It is generated rather
    than committed, so that no private key ever lives in the repository.
    """
    certificate = ssl_directory / 'certs' / 'mail.crt'
    key = ssl_directory / 'mail.key'
    if certificate.exists() and key.exists():
        say_kept("Mail certificate in {}".format(brief_path(ssl_directory)))
        return

    if shutil.which('openssl') is None:
        sys.exit("ERROR: openssl is needed to generate the certificate --mail requires, and it is "
                 "not on the PATH.")

    certificate.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '3650',
         '-subj', '/CN={}'.format(MAIL_SERVICE),
         '-addext', 'subjectAltName=DNS:{},DNS:localhost'.format(MAIL_SERVICE),
         '-keyout', str(key), '-out', str(certificate)],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    # The container reads the key as root, but the host copy should not be world-readable.
    key.chmod(0o600)
    say_wrote(brief_path(certificate), "self-signed, 10 years, for SMTPS")


def env_entries(args, env_file):
    """Every credential the generated file refers to, as (key, value, explanation lines).

    None of these are in docker-compose.yml, because that is the file which gets read, shared and
    pasted into a ticket, while .env is not -- and Compose reads .env by itself, so a ${VAR} in
    the compose file needs nothing extra run to resolve. The values are development defaults and
    are meant to be edited here.
    """
    entries = []
    if args.storage == 'postgres':
        entries.append(('RDB_PASSWORD', RDB_PASSWORD, [
            "Used by both PostgreSQL and IAP, so the two cannot drift apart. Changing it after",
            "the first start needs a fresh volume: it is set when the database is created.",
        ]))
    if args.mail:
        entries.append(('SLING_COMMONS_CRYPTO_PASSWORD', 'password', [
            "The mail feature registers a crypto service that does not start without this.",
        ]))
    if args.keycloak:
        entries.append(('KC_BOOTSTRAP_ADMIN_PASSWORD', 'admin', [
            "The Keycloak admin console login, as `admin` with this password.",
        ]))
        entries.append(('KEYCLOAK_CLIENT_ID', 'iap-sling', []))
        entries.append(('KEYCLOAK_CLIENT_SECRET', '', [
            "Filled in by: ENV_FILE={} {} --write-env".format(
                brief_path(env_file),
                brief_path(HERE.parent / 'dev' / 'keycloak' / 'keycloak_setup.sh')),
            "Until then IAP starts, but no one can sign in.",
        ]))
        entries.append(('IAP_OAUTH_ENCRYPTION_PASSWORD', 'devpassword', [
            "Any value will do for development; it encrypts the stored OAuth tokens.",
        ]))
    return entries


def generate_env_file(env_file, entries):
    """Write the .env holding the credentials, or top up one that is already there.

    An existing file is never rewritten. `keycloak_setup.sh --write-env` fills the client secret
    into it, and a developer may have changed a password, so only missing keys are appended --
    which is also what makes it safe to add `--mail` or `--storage postgres` to a deployment that
    already exists, instead of leaving the compose file pointing at a variable nothing defines.
    """
    if not entries:
        return

    def block(entry):
        key, value, explanation = entry
        return ''.join("# {}\n".format(line) for line in explanation) \
            + "{}={}\n".format(key, value)

    if not env_file.exists():
        env_file.write_text(
            "# Read by Compose, and by the generated docker-compose.yml through ${...}. The\n"
            "# credentials live here rather than there so that the compose file can be shared.\n\n"
            + '\n'.join(block(entry) for entry in entries),
            encoding='utf-8')
        say_wrote(brief_path(env_file), "{} credentials".format(len(entries)))
        return

    existing = env_file.read_text(encoding='utf-8')
    defined = {line.split('=', 1)[0].strip()
               for line in existing.splitlines()
               if '=' in line and not line.lstrip().startswith('#')}
    missing = [entry for entry in entries if entry[0] not in defined]
    if not missing:
        say_kept(brief_path(env_file))
        return

    separator = '' if existing.endswith('\n') else '\n'
    with env_file.open('a', encoding='utf-8') as handle:
        handle.write(separator + '\n' + '\n'.join(block(entry) for entry in missing))
    say_wrote(brief_path(env_file),
              "added {}".format(', '.join(entry[0] for entry in missing)))


### Building the services

def iap_service(args, compose_directory):
    service = {}
    comment(service, "The IAP instance itself. The image carries every module, so what the")
    comment(service, "options below change is only which other containers it is wired to.")
    service['image'] = args.image
    service['container_name'] = 'iap'

    depends_on = {}
    if args.storage == 'postgres':
        # Not a plain dependency: the entrypoint checks the database collation before launching,
        # and can only do that once PostgreSQL is accepting connections.
        depends_on['postgres'] = {'condition': 'service_healthy'}
    elif args.storage == 'mongo':
        depends_on['mongo'] = {'condition': 'service_healthy'}
    if args.keycloak:
        depends_on['keycloak'] = {'condition': 'service_started'}
    if args.mail:
        depends_on[MAIL_SERVICE] = {'condition': 'service_started'}
    if depends_on:
        service['depends_on'] = depends_on

    service['networks'] = ['iap']

    ports = ["{}:8080".format(args.port)]
    if args.debug:
        ports.append("127.0.0.1:5005:5005")
    service['ports'] = ports

    service['environment'] = iap_environment(args)

    volumes = ['iap-data:/opt/iap/.iap-data']
    if args.dev:
        volumes.append("{}:/root/.m2:ro".format(Path.home() / '.m2'))
    if args.mail:
        # The entrypoint imports every certificate under /load_certs into Java's truststore, which
        # is how the self-signed certificate the mail server presents comes to be trusted. Only
        # the certificate directory is mounted -- the private key stays out of this container.
        volumes.append("{}:/load_certs:ro".format(
            relative_to(compose_directory, compose_directory / 'SSL_CONFIG' / 'certs')))
    service['volumes'] = volumes

    service['restart'] = 'unless-stopped'
    return service


def iap_environment(args):
    environment = {}

    if args.storage == 'tar':
        comment(environment, "The repository lives in the container's own filesystem, on the")
        comment(environment, "iap-data volume declared at the bottom of this file.")
        environment['OAK_STORAGE'] = 'tar'
    elif args.storage == 'postgres':
        environment['OAK_STORAGE'] = 'rdb'
        environment['EXTERNAL_RDB_URI'] = "jdbc:postgresql://postgres:5432/{}".format(RDB_DATABASE)
        environment['RDB_USER'] = RDB_USER
        # From .env, like every other credential here: this file is the one that gets shared.
        environment['RDB_PASSWORD'] = '${RDB_PASSWORD}'
    elif args.storage == 'mongo':
        environment['OAK_STORAGE'] = 'mongo'
        environment['EXTERNAL_MONGO_URI'] = 'mongo:27017'
        environment['CUSTOM_MONGO_DB_NAME'] = MONGO_DATABASE

    if args.storage != 'tar':
        comment(environment, "A document store records a cluster node keyed on the hardware")
        comment(environment, "address, which a container changes on every run; pinning it lets a")
        comment(environment, "restart reclaim its own node instead of stranding it. Safe here")
        comment(environment, "only because this file runs exactly one IAP instance.")
        environment['OAK_MACHINE_ID'] = OAK_MACHINE_ID

    if args.keycloak:
        comment(environment, "The back-channel URL is the one IAP resolves inside the network;")
        comment(environment, "the front-channel one is where the browser is sent. They differ")
        comment(environment, "because only the host can reach the published port.")
        environment['BACKEND_KEYCLOAK_REALM_URL'] = \
            "http://keycloak:8080/realms/{}".format(KEYCLOAK_REALM)
        environment['FRONTEND_KEYCLOAK_REALM_URL'] = \
            "http://localhost:{}/realms/{}".format(KEYCLOAK_PORT, KEYCLOAK_REALM)
        # All from .env; the secret is what keycloak_setup.sh --write-env fills in. No inline
        # fallbacks, so .env is the one place a credential is written down.
        environment['KEYCLOAK_CLIENT_ID'] = '${KEYCLOAK_CLIENT_ID}'
        environment['KEYCLOAK_CLIENT_SECRET'] = '${KEYCLOAK_CLIENT_SECRET}'
        environment['IAP_PUBLIC_URL'] = "http://localhost:{}".format(args.port)
        environment['IAP_OAUTH_ENCRYPTION_PASSWORD'] = '${IAP_OAUTH_ENCRYPTION_PASSWORD}'

    if args.mail:
        comment(environment, "Points the mail service at {} port 465 and stops".format(
            MAIL_SERVICE))
        comment(environment, "it checking the server identity, which a throwaway self-signed")
        comment(environment, "certificate cannot satisfy.")
        environment['SMTPS_LOCAL_TEST_CONTAINER'] = 'true'
        # The mail feature registers a crypto service that reads its password from this variable,
        # and does not start without it. The value is in .env.
        environment['SLING_COMMONS_CRYPTO_PASSWORD'] = '${SLING_COMMONS_CRYPTO_PASSWORD}'

    if args.features:
        comment(environment, "Started in addition to the distribution the image already carries.")
        comment(environment, "The entrypoint expands this value as a bash prompt string, so a")
        comment(environment, "coordinate may refer to a container-side variable such as")
        comment(environment, "PLATFORM_VERSION. Any $ below is doubled, which is how Compose is")
        comment(environment, "told to pass it through rather than substituting it itself.")
        # Hence the doubling: Compose interpolates $VAR in this file, and these coordinates are
        # meant to arrive at the entrypoint exactly as they were typed.
        environment['ADDITIONAL_SLING_FEATURES'] = \
            ','.join(args.features).replace('$', '$$')

    if args.debug:
        if args.debug == 'wait':
            comment(environment, "The JVM will not start until a debugger attaches to 5005, so")
            comment(environment, "until one does, this container looks like it is hanging.")
            comment(environment, "Regenerate with --debug attach to start without waiting.")
        else:
            comment(environment, "A debugger may attach to 5005 at any point; startup does not")
            comment(environment, "wait for one.")
        environment['DEBUG'] = args.debug

    return environment


def postgres_service():
    service = {}
    comment(service, "Oak orders node ids by Unicode code point, so this database MUST use C")
    comment(service, "collation. A locale collation looks fine until the first restart and then")
    comment(service, "wedges the instance, so the entrypoint refuses to launch against one. The")
    comment(service, "collation is fixed when the database is created: changing it needs a fresh")
    comment(service, "volume (docker compose down -v).")
    service['image'] = image_for('postgres')
    service['container_name'] = 'postgres'
    service['networks'] = ['iap']
    service['environment'] = {
        'POSTGRES_DB': RDB_DATABASE,
        'POSTGRES_USER': RDB_USER,
        # The same .env value IAP is given, so the two cannot drift apart. An empty one makes
        # this image refuse to start, which is the right way for a missing .env to fail.
        'POSTGRES_PASSWORD': '${RDB_PASSWORD}',
        'POSTGRES_INITDB_ARGS': '--encoding=UTF8 --lc-collate=C --lc-ctype=C',
    }
    service['healthcheck'] = {
        'test': ['CMD-SHELL', "pg_isready -U {} -d {}".format(RDB_USER, RDB_DATABASE)],
        'interval': '5s',
        'timeout': '5s',
        'retries': 20,
    }
    comment(service, "PostgreSQL 18 moved PGDATA into a version-specific subdirectory,")
    comment(service, "and the image's VOLUME up to the parent, so the directory to mount")
    comment(service, "is /var/lib/postgresql. Mounting .../data -- correct before 18, and")
    comment(service, "still what most examples show -- puts the database outside the")
    comment(service, "volume with no warning at all, so the data does not survive.")
    service['volumes'] = ['postgres-data:/var/lib/postgresql']
    service['restart'] = 'unless-stopped'
    return service


def mongo_service():
    service = {}
    comment(service, "The document store IAP writes the repository into.")
    service['image'] = image_for('mongo')
    service['container_name'] = 'mongo'
    service['networks'] = ['iap']
    service['healthcheck'] = {
        'test': ['CMD', 'mongosh', '--quiet', '--eval', "db.adminCommand('ping')"],
        'interval': '5s',
        'timeout': '5s',
        'retries': 20,
    }
    service['volumes'] = ['mongo-data:/data/db']
    service['restart'] = 'unless-stopped'
    return service


def keycloak_service():
    service = {}
    comment(service, "KC_HOSTNAME pins the issuer to the URL the browser uses, while")
    comment(service, "KC_HOSTNAME_BACKCHANNEL_DYNAMIC lets IAP reach the token endpoint")
    comment(service, "in-network. Realm, client and roles are created by")
    comment(service, "tools/dev/keycloak/keycloak_setup.sh once this container is up.")
    service['image'] = image_for('keycloak')
    service['container_name'] = 'keycloak'
    service['command'] = ['start-dev']
    service['networks'] = ['iap']
    service['ports'] = ["127.0.0.1:{}:8080".format(KEYCLOAK_PORT)]
    service['environment'] = {
        'KC_HOSTNAME': "http://localhost:{}".format(KEYCLOAK_PORT),
        'KC_HOSTNAME_BACKCHANNEL_DYNAMIC': 'true',
        'KC_BOOTSTRAP_ADMIN_USERNAME': 'admin',
        # In .env with the rest of the credentials; that is where to read it from when signing
        # in to the admin console.
        'KC_BOOTSTRAP_ADMIN_PASSWORD': '${KC_BOOTSTRAP_ADMIN_PASSWORD}',
    }
    service['restart'] = 'unless-stopped'
    return service


def mail_service(compose_directory):
    service = {}
    comment(service, "Accepts SMTPS on 465 and writes each message to ./mail as an .eml file")
    comment(service, "rather than delivering it. Worth knowing: IAP hands a message to a thread")
    comment(service, "pool and answers 200 either way, so these files are the only proof that")
    comment(service, "mail actually works.")
    service['build'] = {'context': relative_to(compose_directory, HERE / 'mailcatcher')}
    service['container_name'] = MAIL_SERVICE
    service['networks'] = ['iap']
    service['volumes'] = [
        "{}:/certs/mail.crt:ro".format(
            relative_to(compose_directory, compose_directory / 'SSL_CONFIG' / 'certs' / 'mail.crt')),
        "{}:/certs/mail.key:ro".format(
            relative_to(compose_directory, compose_directory / 'SSL_CONFIG' / 'mail.key')),
        "{}:/mail".format(relative_to(compose_directory, compose_directory / 'mail')),
    ]
    service['restart'] = 'unless-stopped'
    return service


def relative_to(compose_directory, target):
    """Path of `target` as the Compose file has to name it.

    Compose resolves bind mounts and build contexts against the directory holding the file rather
    than the working directory. Everything generated sits beside the file, so this is normally a
    short relative path; only `--output` pointing somewhere else makes the tool's own directory
    unreachable that way, and then an absolute path is far more readable than climbing out with a
    row of `..`.
    """
    path = os.path.relpath(target, compose_directory)
    if path.startswith('..'):
        return str(target)
    # Compose reads a bind mount source as a path only when it starts with `.`; anything else it
    # takes for the name of a volume.
    return path if path.startswith('.') else os.path.join('.', path)


def build_document(args, compose_directory):
    services = {'iap': iap_service(args, compose_directory)}
    if args.storage == 'postgres':
        services['postgres'] = postgres_service()
    elif args.storage == 'mongo':
        services['mongo'] = mongo_service()
    if args.keycloak:
        services['keycloak'] = keycloak_service()
    if args.mail:
        services[MAIL_SERVICE] = mail_service(compose_directory)

    volumes = {'iap-data': None}
    if args.storage == 'postgres':
        volumes['postgres-data'] = None
    elif args.storage == 'mongo':
        volumes['mongo-data'] = None

    return {'services': services, 'volumes': volumes, 'networks': {'iap': None}}


def next_steps(args, compose_directory):
    """What the reader must do, as (title, [commands]) pairs, and what to expect afterwards.

    Returned as data rather than ready-made lines so that the renderer can set the commands
    apart from the prose -- finding them was the whole difficulty with a flat list. The order is
    meaningful: Keycloak has to be running before its realm can be created.
    """
    actions = []
    if args.keycloak:
        actions.append(("Start Keycloak on its own, then create the realm and client", [
            "docker compose up -d keycloak",
            "ENV_FILE={} {} --write-env".format(
                brief_path(compose_directory / '.env'),
                brief_path(HERE.parent / 'dev' / 'keycloak' / 'keycloak_setup.sh')),
        ]))
    actions.append(("Bring everything up", [
        "docker compose up -d --build" if args.mail else "docker compose up -d",
    ]))

    notes = ["IAP will be at {}".format(
        paint("http://localhost:{}".format(args.port), 'bold', 'blue'))]
    if args.mail:
        notes.append("Messages IAP sends land in ./mail as .eml files.")
    if args.debug == 'wait':
        notes.append("IAP will {} until a debugger attaches: {}".format(
            paint("not start", 'bold', 'yellow'), paint("jdb -attach 5005", 'cyan')))
    elif args.debug == 'attach':
        notes.append("A debugger can attach whenever you like: {}".format(
            paint("jdb -attach 5005", 'cyan')))
    return actions, notes


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = parse_args(argv)

    output = Path(args.output).resolve()
    if output == IMAGE_PINS:
        # It is also a docker-compose.yml, and overwriting it would throw away the pins this
        # very run just read, along with what Dependabot watches.
        sys.exit("ERROR: {} is where the image versions are pinned, not somewhere to generate "
                 "into. Pick another --output.".format(IMAGE_PINS))
    compose_directory = output.parent
    compose_directory.mkdir(parents=True, exist_ok=True)

    if args.mail:
        generate_mail_certificate(compose_directory / 'SSL_CONFIG')
        (compose_directory / 'mail').mkdir(parents=True, exist_ok=True)
    # Not only for --keycloak any more: a Postgres or mail deployment has credentials of its own,
    # and they are kept out of the compose file the same way.
    env_file = compose_directory / '.env'
    generate_env_file(env_file, env_entries(args, env_file))

    header = [
        "Generated by tools/deploy/generate_compose.py -- edit freely, or regenerate with:",
        "  generate_compose.py {}".format(' '.join(argv)),
    ]
    output.write_text(dump(build_document(args, compose_directory), header), encoding='utf-8')

    say_wrote(brief_path(output))

    actions, notes = next_steps(args, compose_directory)
    heading("Next steps")
    for number, (title, commands) in enumerate(actions, start=1):
        print("\n  {} {}".format(paint("{}.".format(number), 'bold', 'yellow'), title))
        for line in commands:
            command(line)

    print("\n{}".format(paint(GLYPH['soft'] * WIDTH, 'dim')))
    for note in notes:
        print("  {}".format(note))
    print()
    return 0


if __name__ == '__main__':
    sys.exit(main())
