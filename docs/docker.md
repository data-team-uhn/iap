# Docker packaging

The `packaging/docker` module (activated with `-Pdocker`) builds the `iap/iap` Docker image.
One image definition serves two flavors, differing only in how much of the artifact
repository is baked in:

```
mvn clean install -Pdocker                            # developer flavor: small and fast
mvn clean install -Pdocker -Ddocker.production=true   # production flavor: fully self-contained
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
`EXTERNAL_RDB_URI`/`RDB_DRIVER`/`RDB_USER`/`RDB_PASSWORD`, `SMTPS_*` (mail),
`DEBUG` (JDWP on port 5005), `JAVA_MEMORY_LIMIT_MB`, and a `/volume_mounted_init.sh` hook.

With `OAK_STORAGE=rdb` the repository lives in a relational database, which must be reachable
and must hold a database the connecting user may create tables in — Oak creates its own tables
on first start. Only the PostgreSQL JDBC driver is bundled in the image; `RDB_DRIVER` exists
for deployments that add another vendor's driver through `ADDITIONAL_SLING_FEATURES`:

```
docker run --rm -e OAK_STORAGE=rdb -e EXTERNAL_RDB_URI=jdbc:postgresql://db:5432/iap \
  -e RDB_USER=iap -e RDB_PASSWORD=secret -p 8080:8080 -it iap/iap
```

## The metadata layer

`/metadata` inside the image supports security audits of production deployments, and is
always present and current:

- `build-info.txt` — version, git commit, and build timestamp;
- `core_tar.json` / `core_mongo.json` / `core_rdb.json` — the aggregated feature models, the
  complete versioned inventory of every Java artifact in the deployment;
- `yarn.lock` — the complete inventory of the frontend JavaScript dependencies;
- `logo.svg` — the platform logo shipped with this build.
