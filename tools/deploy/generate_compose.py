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
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

# Pinned so that regenerating does not silently change what gets deployed.
POSTGRES_IMAGE = "postgres:18-alpine"
MONGO_IMAGE = "mongo:8"
KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.7.0"

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
    parser.add_argument('--output', default='docker-compose.yml',
                        help="Where to write the generated file [default: docker-compose.yml]")

    return parser.parse_args(argv)


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
        print("Keeping the existing mail certificate in {}".format(ssl_directory))
        return

    if shutil.which('openssl') is None:
        sys.exit("ERROR: openssl is needed to generate the certificate --mail requires, and it is "
                 "not on the PATH.")

    certificate.parent.mkdir(parents=True, exist_ok=True)
    print("Generating a self-signed certificate for the mail server")
    subprocess.run(
        ['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '3650',
         '-subj', '/CN={}'.format(MAIL_SERVICE),
         '-addext', 'subjectAltName=DNS:{},DNS:localhost'.format(MAIL_SERVICE),
         '-keyout', str(key), '-out', str(certificate)],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    # The container reads the key as root, but the host copy should not be world-readable.
    key.chmod(0o600)


def generate_env_file(env_file):
    """Leave a .env for keycloak_setup.sh to fill in.

    Compose reads .env by itself, and the generated file takes the client secret from it rather
    than inlining it: a secret does not belong in a file this easy to paste into a ticket.
    """
    if env_file.exists():
        print("Keeping the existing {}".format(env_file))
        return
    env_file.write_text(
        "# Filled in by: ENV_FILE={} tools/dev/keycloak/keycloak_setup.sh --write-env\n"
        "# Until then IAP starts without a working OIDC client.\n"
        "KEYCLOAK_CLIENT_ID=iap-sling\n"
        "KEYCLOAK_CLIENT_SECRET=\n"
        "# Any password will do for local development; it encrypts the stored OAuth tokens.\n"
        "IAP_OAUTH_ENCRYPTION_PASSWORD=devpassword\n".format(env_file),
        encoding='utf-8')
    print("Wrote {} for keycloak_setup.sh to fill in".format(env_file))


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
        environment['RDB_PASSWORD'] = RDB_PASSWORD
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
        # Both come from .env, which keycloak_setup.sh --write-env fills in.
        environment['KEYCLOAK_CLIENT_ID'] = '${KEYCLOAK_CLIENT_ID:-iap-sling}'
        environment['KEYCLOAK_CLIENT_SECRET'] = '${KEYCLOAK_CLIENT_SECRET:-}'
        environment['IAP_PUBLIC_URL'] = "http://localhost:{}".format(args.port)
        environment['IAP_OAUTH_ENCRYPTION_PASSWORD'] = \
            '${IAP_OAUTH_ENCRYPTION_PASSWORD:-devpassword}'

    if args.mail:
        comment(environment, "Points the mail service at {} port 465 and stops".format(
            MAIL_SERVICE))
        comment(environment, "it checking the server identity, which a throwaway self-signed")
        comment(environment, "certificate cannot satisfy.")
        environment['SMTPS_LOCAL_TEST_CONTAINER'] = 'true'
        # The mail feature registers a crypto service that reads its password from this variable,
        # and does not start without it.
        environment['SLING_COMMONS_CRYPTO_PASSWORD'] = 'password'

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
    service['image'] = POSTGRES_IMAGE
    service['container_name'] = 'postgres'
    service['networks'] = ['iap']
    service['environment'] = {
        'POSTGRES_DB': RDB_DATABASE,
        'POSTGRES_USER': RDB_USER,
        'POSTGRES_PASSWORD': RDB_PASSWORD,
        'POSTGRES_INITDB_ARGS': '--encoding=UTF8 --lc-collate=C --lc-ctype=C',
    }
    service['healthcheck'] = {
        'test': ['CMD-SHELL', "pg_isready -U {} -d {}".format(RDB_USER, RDB_DATABASE)],
        'interval': '5s',
        'timeout': '5s',
        'retries': 20,
    }
    service['volumes'] = ['postgres-data:/var/lib/postgresql/data']
    service['restart'] = 'unless-stopped'
    return service


def mongo_service():
    service = {}
    comment(service, "The document store IAP writes the repository into.")
    service['image'] = MONGO_IMAGE
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
    service['image'] = KEYCLOAK_IMAGE
    service['container_name'] = 'keycloak'
    service['command'] = ['start-dev']
    service['networks'] = ['iap']
    service['ports'] = ["127.0.0.1:{}:8080".format(KEYCLOAK_PORT)]
    service['environment'] = {
        'KC_HOSTNAME': "http://localhost:{}".format(KEYCLOAK_PORT),
        'KC_HOSTNAME_BACKCHANNEL_DYNAMIC': 'true',
        'KC_BOOTSTRAP_ADMIN_USERNAME': 'admin',
        'KC_BOOTSTRAP_ADMIN_PASSWORD': 'admin',
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
    steps = []
    if args.keycloak:
        steps.append("Start Keycloak on its own first, then create the realm and client:")
        steps.append("  docker compose up -d keycloak")
        steps.append("  ENV_FILE={} {} --write-env".format(
            compose_directory / '.env', HERE.parent / 'dev' / 'keycloak' / 'keycloak_setup.sh'))
    steps.append("Bring everything up with:")
    steps.append("  docker compose up -d --build" if args.mail else "  docker compose up -d")
    steps.append("IAP will be at http://localhost:{}".format(args.port))
    if args.mail:
        steps.append("Messages IAP sends land in ./mail as .eml files.")
    if args.debug == 'wait':
        steps.append("IAP will not start until a debugger attaches: jdb -attach 5005")
    elif args.debug == 'attach':
        steps.append("A debugger can attach whenever you like: jdb -attach 5005")
    return steps


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = parse_args(argv)

    output = Path(args.output).resolve()
    compose_directory = output.parent
    compose_directory.mkdir(parents=True, exist_ok=True)

    if args.mail:
        generate_mail_certificate(compose_directory / 'SSL_CONFIG')
        (compose_directory / 'mail').mkdir(parents=True, exist_ok=True)
    if args.keycloak:
        generate_env_file(compose_directory / '.env')

    header = [
        "Generated by tools/deploy/generate_compose.py -- edit freely, or regenerate with:",
        "  generate_compose.py {}".format(' '.join(argv)),
    ]
    output.write_text(dump(build_document(args, compose_directory), header), encoding='utf-8')

    print("Wrote {}".format(output))
    for line in next_steps(args, compose_directory):
        print("  {}".format(line))
    return 0


if __name__ == '__main__':
    sys.exit(main())
