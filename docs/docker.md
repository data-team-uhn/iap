# Docker packaging

`-Pdocker` builds two images: `iap/iap`, the platform itself, described below, and
`iap/docling`, the document processing daemon — see [The docling image](#the-docling-image).

## The `iap/iap` image

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
`tar` or `mongo` chosen by the `OAK_FILESYSTEM` environment variable), resolving artifacts
from, in order:

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

Other supported environment variables: `OAK_FILESYSTEM` (TAR segment store instead of
MongoDB), `EXTERNAL_MONGO_URI`/`MONGO_AUTH`/`CUSTOM_MONGO_DB_NAME`, `SMTPS_*` (mail),
`DEBUG` (JDWP on port 5005), `JAVA_MEMORY_LIMIT_MB`, and a `/volume_mounted_init.sh` hook.

## The metadata layer

`/metadata` inside the image supports security audits of production deployments, and is
always present and current:

- `build-info.txt` — version, git commit, and build timestamp;
- `core_tar.json` / `core_mongo.json` — the aggregated feature models, the complete versioned
  inventory of every Java artifact in the deployment;
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
