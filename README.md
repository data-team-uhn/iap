# Institutional Authorization Platform (IAP)

IAP is a software tool for streamlining research proposal authorization.

It runs as an [Apache Sling](https://sling.apache.org/) application (OSGi on Apache Felix, with an Apache Jackrabbit Oak content repository), assembled and launched through the Sling Feature Model. The user interface is a React single-page app.

## Prerequisites

- **Java 21**
- **Maven 3.8.7+**
- **Python 3** — the start process is implemented in Python (`start.py`). Installing the optional `psutil` module enables a more robust bind check (not used on WSL or macOS, where a simpler check is applied automatically).
- **MongoDB** — only if you run with `--mongo` (see below); the default storage needs nothing extra.

## Building

```bash
mvn clean install
```

Tests are **skipped by default** for fast local builds. Useful Maven flags:

| Flag | Effect |
| --- | --- |
| `-DskipTests=false -Dmaven.test.skip=false` | Run the test suites (Java via Surefire, and the frontend Vitest suite in the `test` phase). Run these before pushing. |
| `-DwebpackArguments=--mode=production` | Build the frontend in production mode (default is `development`). |
| `-Dcheckstyle.skip=true` | Skip the Checkstyle checks. |
| `-Denforcer.skip=true` | Skip the Maven Enforcer checks. |

## Running

On Linux, macOS, or WSL:

```bash
./start.sh
```

On Windows (from `cmd` or PowerShell):

```bat
start.bat
```

Both are thin wrappers around `start.py`, where all of the start logic lives — they only locate a Python interpreter and delegate to it, so the two platforms cannot drift apart.

IAP will be available at <http://localhost:8080> once it has started. Press `Ctrl+C` to stop it. Runtime state (repository, cache, logs) is written to `.iap-data/`.

### Start options

| Option | Description |
| --- | --- |
| `-p`, `--port <PORT>` | Port to bind to (default `8080`). |
| `--data <DIR>` | Directory for the runtime state — repository, cache, logs (default `.iap-data`). Each concurrently running instance needs its own data directory and port; the repository takes an exclusive lock on its data directory, so a second instance pointed at the same one will hang waiting for the lock. |
| `--mongo` | Use a MongoDB document store for the repository instead of the default file-based (TAR/segment) store. Requires a running MongoDB instance. |
| `--debug` | Enable Java remote debugging (JDWP) on port `5005`. Startup **pauses until a debugger attaches** — connect with `jdb -attach 5005` (or your IDE). |
| `--test` | Additionally load test content (the `iap-test-data` feature). |
| `--permissions <MODE>` | Permissions scheme to apply when resolving project features (used together with `--project`). |
| `-P`, `--project <name[,name2,...]>` | Launch one or more IAP *projects*. Each `<name>` resolves to the `iap4<name>` artifact and its dependency features (the `iap4` prefix is optional). |

Notes:

- Any other arguments are passed straight through to the Sling Feature Launcher. The literal token `VERSION` in an argument is replaced with the current platform version.
- The `PROJECT_VERSION` environment variable overrides the version used to resolve `--project` features (it defaults to the platform version).

### Examples

```bash
./start.sh                 # default: file-based storage on port 8080
./start.sh -p 8888         # run on a custom port
./start.sh --mongo         # use a MongoDB-backed repository
./start.sh --debug         # wait for a debugger to attach on port 5005
./start.sh -P myproject    # launch the "iap4myproject" project

# A second instance next to a running one: separate port AND data directory
./start.sh --test -p 8089 --data .iap-data-test
```

(On Windows, replace `./start.sh` with `start.bat` — the options are identical.)

## Deploying to a running instance

Once an instance is up, you can rebuild and redeploy a single bundle in place — without a full restart — using the `autoInstallBundle` profile. Run it from the directory of the module you want to redeploy:

```bash
cd modules/<some-backend-module>
mvn clean install -PautoInstallBundle       # hot-deploys just that module's OSGi bundle

cd aggregated-frontend
mvn clean install -PautoInstallBundle       # rebuilds the frontend and redeploys the UI bundle
```

The profile uses the [sling-maven-plugin](https://sling.apache.org/documentation/development/sling-maven-plugin.html) to upload the freshly built bundle to the running instance. By default it targets `http://localhost:8080` as `admin:admin`; override with:

| Flag | Effect |
| --- | --- |
| `-Dsling.url=https://host:8443/system/console` | Target a different instance (the URL must end with `/system/console`). |
| `-Dsling.password=<password>` | Use a different admin password. |

This redeploys **code** (Java bundles, the frontend JS). It does **not** re-run a bundle's initial **content** if that content already exists in the repository. To deploy a new content node — such as a new `iap:Extension` — into a running instance, post it directly with [`post-extension.sh`](./tools/dev/extension-manager/post-extension.sh), one of the [extension-manager dev utilities](./tools/dev/extension-manager/).

## Extending the UI

The user interface is composed of extensions plugged into extension points — the page shell (pinned frame bars and side rails, scrolling page regions), the application bar, the routed views, and the dashboard widgets are all extension points. See **[docs/ui-extensions.md](./docs/ui-extensions.md)** for the catalogue of available points and recipes for contributing extensions or defining new points.
