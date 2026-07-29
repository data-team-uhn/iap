# Docker packaging

`-Pdocker` builds two images: `iap/iap`, the platform itself, described below, and
`iap/docling`, the document processing daemon — see [The docling image](#the-docling-image).

## The `iap/iap` image

The `packaging/docker` module (activated with `-Pdocker`) builds the `iap/iap` Docker image.
One image definition serves two flavors, differing only in how much of the artifact
repository is baked in:

```
mvn clean install -Pdocker              # developer flavor: small and fast
mvn clean install -Pdocker,production   # production flavor: fully self-contained
```

## How the image works

The container starts the Sling Feature Launcher on the aggregated feature
(`mvn:io.uhndata.iap/iap-packaging-slingfeature/<version>/slingosgifeature/core_<storage>`,
one of `tar`, `mongo` or `rdb` named by the `OAK_STORAGE` environment variable — without it,
`tar` when `OAK_FILESYSTEM` is set and `mongo` otherwise), resolving artifacts from, in order:

1. `/opt/iap/mvnrepo` — the project's own artifacts, including **every feature file produced
   by the repository**, copied from the build's `.mvnrepo`;
2. `/opt/iap/artifacts` — the third-party artifact repository; empty in the developer flavor,
   complete in the production flavor;
3. `/root/.m2/repository` — a volume-mountable host Maven repository;
4. the remote repositories, as a last resort.

**Developer flavor**: mount your local repository for instant, offline starts right after a
host build:

```
docker run --rm --volume ~/.m2:/root/.m2 -e OAK_FILESYSTEM=true -p 8080:8080 -it iap/iap
```

Without the mount, third-party artifacts are downloaded on first start and cached in the
`/opt/iap/.iap-data` volume.

**Production flavor**: the build additionally harvests every feature file built by the
reactor and materializes all their referenced artifacts (via the `slingfeature-maven-plugin`
`repository` goal) into `/opt/iap/artifacts`, one deduplicated Maven-layout repository. The
image needs no network access and no mounts, and — because *all* repository features are
embedded, not only the ones aggregated into the core feature — optional features can be
enabled at runtime without network access.

## Enabling optional features

Extra features can be activated when starting the container:

- `ADDITIONAL_SLING_FEATURES` — a comma-separated list of feature coordinates passed straight
  to the launcher, e.g. `mvn:io.uhndata.iap/iap-something/0.1.0-SNAPSHOT/slingosgifeature`;
- `PROJECT_NAME` / `PROJECT_VERSION` — selects a per-project distribution; the features it
  requires are listed in `/sling-features.json` (currently empty, pending the first project
  distributions).

Other supported environment variables: `OAK_STORAGE` (`tar`, `mongo` or `rdb`) and the older
`OAK_FILESYSTEM` (TAR segment store instead of MongoDB),
`EXTERNAL_MONGO_URI`/`MONGO_AUTH`/`CUSTOM_MONGO_DB_NAME`,
`EXTERNAL_RDB_URI`/`RDB_DRIVER`/`RDB_USER`/`RDB_PASSWORD`, `OAK_MACHINE_ID` (see below),
`SMTPS_*` (mail), `DEBUG` (JDWP on port 5005), `JAVA_MEMORY_LIMIT_MB`, and a
`/volume_mounted_init.sh` hook.

With `OAK_STORAGE=rdb` the repository lives in a relational database, which must be reachable
and must hold a database named `iap` the connecting user may create tables in — Oak creates
its own tables on first start. Only the PostgreSQL JDBC driver is bundled in the image;
`RDB_DRIVER` exists for deployments that add another vendor's driver through `ADDITIONAL_SLING_FEATURES`:

```
docker run --rm -e OAK_STORAGE=rdb -e EXTERNAL_RDB_URI=jdbc:postgresql://db:5432/iap \
  -e RDB_USER=iap -e RDB_PASSWORD=secret -p 8080:8080 -it iap/iap
```

**PostgreSQL collation — required**: the database **must** be created with `C` (or `POSIX`)
collation. Oak's `RDBDocumentStore` orders the node `id` column by Unicode code point (its queries
use `ORDER BY` on `id`, and the primary-key index ordering must match); a locale collation such as
`en_US.utf8` — the default the official `postgres` image initializes — orders those ids differently,
so Oak mis-resolves already-persisted data. The symptom is insidious: the *first* start succeeds
(the database is empty, nothing to read back), but *every restart* then fails during activation with
`ClusterRepositoryInfo getOrCreateId: both setting and then reading of /:clusterConfig/:clusterId
failed` and the instance hangs. Create the database accordingly:

```
CREATE DATABASE iap OWNER iap TEMPLATE template0 ENCODING 'UTF8' LC_COLLATE 'C' LC_CTYPE 'C';
```

For the official `postgres` image, set `POSTGRES_INITDB_ARGS=--encoding=UTF8 --lc-collate=C --lc-ctype=C`
on a **fresh** data volume (collation is fixed when the database is created and cannot be changed
afterwards). The entrypoint verifies this on start and refuses to launch against a non-`C` database.
See the [Oak RDB DocumentStore documentation](https://jackrabbit.apache.org/oak/docs/nodestore/document/rdb-document-store.html).

**Cluster node identity**: both document-store back-ends, `mongo` and `rdb`, record a *cluster
node* in the database, identified by the hardware address and the working directory, and reclaim
it on the next start. A container is given a fresh MAC address every run, so without help a
restart that happens before the previous lease expires — two minutes — cannot reclaim or wait for
the old cluster node. It takes a new cluster id instead and leaves the old entry behind, marked
active forever and therefore never recovered. Setting `OAK_MACHINE_ID` to any stable value (e.g.
`ca2d50000001`) pins the address, so that a restarted container reclaims its own cluster node.

It is **opt-in, with no default, on purpose**. Several IAP containers routinely share one
database, and they distinguish themselves precisely by having different hardware addresses; a
shared default would collapse them all onto a single cluster node, which corrupts the repository
rather than merely failing. Set it only for a single-instance deployment, or give every instance
its own distinct value.

`start.py` reads `OAK_MACHINE_ID` too, for the same reason, when it is set in the environment.

## The metadata layer

`/metadata` inside the image supports security audits of production deployments, and is
always present and current:

- `build-info.txt` — version, git commit, and build timestamp;
- `core_tar.json` / `core_mongo.json` / `core_rdb.json` — the aggregated feature models, the
  complete versioned inventory of every Java artifact in the deployment;
- `yarn.lock` — the complete inventory of the frontend JavaScript dependencies;
- `logo.svg` — the platform logo shipped with this build.

## The docling image

`modules/documents/processing` builds `iap/docling`, the Python daemon that converts uploaded
PDF/DOC/DOCX documents into cleaned, chunked Markdown. The `docker` profile therefore builds
both images, as in `mvn clean install -Pdocker`. To build only this one:

```
mvn install -Pdocker -pl modules/documents/processing
```

**Note**: the image is roughly 8.7 GB and takes a while to build. This is because it bakes the Docling
model weights into the image at build time, as without them the first conversion would reach out to
huggingface.co and fail, and the container would report healthy until the first request. The
weights are saved in `DOCLING_ARTIFACTS_PATH`, and `HF_HUB_OFFLINE` is set afterwards so that
the runtime stays offline after downloading.

The daemon publishes **no port**. `modules/documents/processing/docker-compose.yml` runs it as a
Compose service with no `ports:` entry, so IAP reaches it by service name at
`http://docling:18765` and nothing outside that network can access it. `POST /parse` takes the document
in the request body and returns the Markdown and chunk tree in the response.

See `modules/documents/processing/requirements.md` for the endpoints, the system properties IAP
uses to reach the daemon, and how to run it by hand for local work.
