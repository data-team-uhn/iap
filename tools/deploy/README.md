# Deploying IAP with the services it talks to

`generate_compose.py` writes a Docker Compose file running the IAP container together with
whatever it needs to talk to — a database, an identity provider, a mail server. It needs
Python 3 and nothing else; the file it writes is commented and meant to be
edited afterwards, so the generator is a starting point rather than something to keep in the loop.

```bash
# The simplest thing that runs: repository on the container filesystem, nothing else
python3 generate_compose.py
docker compose up -d

# A realistic deployment: PostgreSQL for storage, Keycloak for sign-in
python3 generate_compose.py --storage postgres --keycloak

# Everything a developer might want to poke at
python3 generate_compose.py --storage postgres --keycloak --mail --dev --debug
```

Each run rewrites `docker-compose.yml` in this directory. `docker compose down` stops everything
and keeps the repository; `./cleanup.sh` throws the whole thing away and leaves the directory as
git has it.

Every run ends by printing the commands still to be typed, numbered and set apart from the prose,
since the order matters when Keycloak is involved. Colour is used when the output is going to a
terminal and dropped when it is redirected; `NO_COLOR=1` turns it off regardless and
`FORCE_COLOR=1` keeps it through a pipe.

## What the options do

Every option chooses **a container to run alongside IAP**, and sets the environment variables IAP
needs to reach it.

| Option | Adds | Wires IAP to it with |
| --- | --- | --- |
| `--storage tar` (default) | nothing — the repository lives in the container | `OAK_STORAGE=tar` |
| `--storage postgres` | a PostgreSQL container | `OAK_STORAGE=rdb`, `EXTERNAL_RDB_URI`, `RDB_USER`, `RDB_PASSWORD` |
| `--storage mongo` | a MongoDB container | `OAK_STORAGE=mongo`, `EXTERNAL_MONGO_URI`, `CUSTOM_MONGO_DB_NAME` |
| `--keycloak` | a Keycloak container | the two realm URLs, `KEYCLOAK_CLIENT_ID`/`SECRET`, `IAP_PUBLIC_URL` |
| `--mail` | an SMTPS server that files messages away | `SMTPS_LOCAL_TEST_CONTAINER`, and the certificate to trust |

The rest only describe the IAP container itself: `--image` (default `iap/iap`), `--port` (default
8080), `--dev` to mount `~/.m2` read-only, as the developer flavour of the image needs in order to
resolve third-party artifacts, `--debug` to open the JDWP port, `--feature` to start something the
core distribution does not carry (below), and `--output` to write the file somewhere other than
here.

## Extra features

`--feature` names a feature to launch on top of the distribution the image already carries, and is
repeatable:

```bash
python3 generate_compose.py \
  --feature mvn:io.uhndata.iap/iap-something/0.1.0-SNAPSHOT/slingosgifeature \
  --feature mvn:com.example/example-jdbc-driver/1.0.0/slingosgifeature
```

The coordinates arrive as the comma-separated `ADDITIONAL_SLING_FEATURES` the entrypoint expects,
and go straight to the launcher's `-f`. This is **not** how to switch IAP's own modules on: the
image ships every one of them already, and nothing here needs enabling. It is for what the image
does not contain — another vendor's JDBC driver, say, for `--storage postgres` against a different
database (see `RDB_DRIVER` in `docs/docker.md`).

Every feature in the repository is embedded in the image, so an `io.uhndata.iap` coordinate
resolves without network access; anything else has to be reachable from inside the container.

Two constraints come from the entrypoint rather than from here, and the generator refuses a value
that trips either: a coordinate cannot contain whitespace, because `docker_entry.sh` tests the
variable unquoted, and it cannot contain a comma, because that is the separator. A `$` is passed
through — it appears doubled in the generated file, which is how Compose is told not to substitute
it, so that the entrypoint's own expansion sees it and a coordinate can refer to a container-side
variable such as `PLATFORM_VERSION`.

## Debugging

`--debug` publishes JDWP on `127.0.0.1:5005`, in one of two modes:

```bash
python3 generate_compose.py --debug attach   # start normally, attach whenever
python3 generate_compose.py --debug wait     # do not start until a debugger attaches
python3 generate_compose.py --debug          # same as --debug wait
```

`attach` is what debugging a running instance wants: everything comes up as usual and
`jdb -attach 5005` connects whenever you get round to it.

`wait` is for debugging startup itself — the JVM stops before it does anything at all and stays
there until a debugger connects. Worth knowing before you use it in a detached stack: a container
waiting like this is indistinguishable from one that has hung, and anything ordered after IAP
waits with it. It says what it is waiting for in `docker compose logs iap`.

## Storage

`tar` keeps the repository in the container's own filesystem, on a Docker volume. It needs no
second container and is the right choice for trying something out.

`postgres` and `mongo` are document stores, and both carry a wrinkle the generated file handles for
you:

- **PostgreSQL must use `C` collation.** Oak orders node ids by Unicode code point, so a locale
  collation — including the `en_US.utf8` the official image would otherwise pick — appears to work
  until the first restart and then wedges the instance for good. The generated file passes
  `POSTGRES_INITDB_ARGS=--encoding=UTF8 --lc-collate=C --lc-ctype=C`, and IAP's entrypoint refuses
  to launch against a database that got it wrong. Collation is fixed when the database is created,
  so correcting it means `docker compose down -v` and starting over.
- **A restart has to reclaim its cluster node.** Both stores identify a cluster node by hardware
  address, which a container changes on every run, so a quick restart would otherwise strand the
  old entry as permanently active. The generated file sets `OAK_MACHINE_ID` to pin it. That is only
  safe because this file runs exactly one instance — see `docs/docker.md` before copying it into
  anything that runs several.

IAP does not start until the database reports healthy, because the entrypoint checks the collation
before launching and needs something to connect to.

## Keycloak

Keycloak has to be running before its realm can be created, so this one is a two-step affair:

```bash
python3 generate_compose.py --storage postgres --keycloak
docker compose up -d keycloak
ENV_FILE=$PWD/.env ../dev/keycloak/keycloak_setup.sh --write-env
docker compose up -d
```

`keycloak_setup.sh` (in `tools/dev/keycloak/`) creates the realm, the client and the roles,
then writes the client id and secret into `.env`, which Compose reads by itself. The secret
stays out of `docker-compose.yml` so the generated file can be shared without leaking it. Until
that step has run, IAP starts but no one can sign in.

The two realm URLs differ on purpose: IAP resolves `keycloak:8080` inside the Docker network, while
the browser is redirected to the published `localhost:8084`. `docs/keycloak-oidc.md` explains the
front/back-channel split in full.

## Mail

`--mail` adds a small SMTPS server (`mailcatcher/`) that writes every message it is handed into
`./mail` as an `.eml` file instead of delivering it. Nothing leaves the machine, which is the
point.

The server is about a hundred lines of standard-library Python: it accepts any credentials,
says yes to everything, and files the result away. That also makes it something to keep well away
from any machine that is not a development one.

The generator creates a self-signed certificate under `SSL_CONFIG/` on first use and never
regenerates it. The certificate is mounted into the IAP container at `/load_certs`, where the
entrypoint imports it into Java's truststore; the private key goes only to the mail server.

To send something through it:

```bash
curl -u admin:admin \
  "http://localhost:8080/content.emailtest.html?fromEmail=iap@example.org&fromName=IAP&toEmail=you@example.org&toName=You"
ls mail/
```

**Read the files, not the response.** IAP hands a message to a thread pool and answers `200`
whether or not it was ever built and sent, so an empty `mail/` is the only way to find out that
mail is broken. `docker compose logs smtps_test_container` names each message as it arrives.

## Cleaning up

`docker compose down` stops the containers and keeps everything else, which is what you want
between runs — the repository survives, so the next `up` carries on where it left off.

`./cleanup.sh` is for when you want none of it any more:

```bash
./cleanup.sh              # asks first
./cleanup.sh --yes        # does not
./cleanup.sh --yes ../..  # for a Compose file written elsewhere with --output
```

It removes the containers and the network, the volumes holding the repository, the image built
for the mail server, and then the generated files — `docker-compose.yml`, `.env`, `SSL_CONFIG/`
and `mail/`. Pulled images like `postgres` and `iap/iap` are left alone; they are shared with
everything else on the machine and cost nothing to keep.

What is left is a directory holding only what git tracks, so the next `generate_compose.py` starts
from nothing — including a new mail certificate and, if Keycloak is in use, a realm that has to be
set up again.

It is safe to run when there is nothing to do, and on a machine without Docker it removes the
files and says which containers it could not reach.

## Keeping the images current

The images the generator deploys are pinned in `images/docker-compose.yml`, and read back from
there at generation time. That file is never run: it exists so the versions live somewhere
Dependabot can see them. Dependabot reads image versions out of Compose files and Dockerfiles and
would never find them in a Python constant, so a pin kept in the script would quietly go stale.

Two entries in `.github/dependabot.yml` cover it, weekly:

| Watches | Ecosystem | For |
| --- | --- | --- |
| `/tools/deploy/images` | `docker-compose` | `postgres`, `mongo`, `keycloak` |
| `/tools/deploy/mailcatcher` | `docker` | the mail server's `python` base image |

A Dependabot pull request against `images/docker-compose.yml` therefore changes what the next
`generate_compose.py` actually deploys, rather than editing a file nobody reads. Regenerate after
merging one; a `docker-compose.yml` already generated keeps whatever it was written with.

The file has to be called `docker-compose.yml` and live in a directory of its own — Dependabot
looks for that name, and does not pick up other `docker-compose*.yml` variants
([dependabot-core#12134](https://github.com/dependabot/dependabot-core/issues/12134)). It is in
`images/` rather than beside the script because the generated file next door claims the same name.

The IAP image is deliberately not pinned there: it is built from this repository rather than
pulled, and `--image` decides which one to run.

## What was left behind

This is a slimmed-down port of
[cards-deploy-tool](https://github.com/data-team-uhn/cards-deploy-tool). Most of what that tool
does has no counterpart here:

- **MS SQL, for Clarity imports.** IAP has no Clarity integration and no relational-import module
  at all, so the container would have had nothing to connect to. Worth revisiting if one is ever
  written.
- **Adminer, the web database browser.** With no external database for IAP to talk to, the only
  thing left to point it at was Oak's own tables, which are not meant to be read by hand. Left out
  until there is a database worth browsing; `docker compose exec postgres psql -U iap` covers the
  occasional look in the meantime.
- **MongoDB shards and replicas, Percona, encryption at rest, Vault.** A cluster is a production
  concern, and IAP has no production deployment yet to shape it.
- **The SSL/SAML reverse proxy, the split admin/user ports, the forward proxy.** IAP authenticates
  through OIDC rather than SAML, and terminating TLS is the job of whatever fronts a real
  deployment.
- **MinIO/S3, NeuralCR, the backup recorder, Slack performance webhooks.** No module reads any of
  them. Chat notifications are configured in the repository, not through the container — see
  `docs/notifications.md`.

These were dropped because nothing in IAP consumes them today, not because they were bad ideas.
Adding one back means adding the container *and* the environment variables IAP would read it
through — and `packaging/docker/docker_entry.sh` is where the second half lives.
