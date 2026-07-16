# Institutional Authorization Platform (IAP)

IAP is a software tool for streamlining research proposal authorization.

It runs as an [Apache Sling](https://sling.apache.org/) application (OSGi on Apache Felix, with an Apache Jackrabbit Oak content repository), assembled and launched through the Sling Feature Model. The user interface is a React single-page app.

## Prerequisites

- **Java 21**
- **Maven 3.8.7+**
- **Python 3** — used by `start.sh` for the TCP port checks. Installing the optional `psutil` module enables a more robust bind check (not available on WSL or macOS, where a simpler check is used automatically).
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

```bash
./start.sh
```

IAP will be available at <http://localhost:8080> once it has started. Press `Ctrl+C` to stop it. Runtime state (repository, cache, logs) is written to `.iap-data/`.

### `start.sh` options

| Option | Description |
| --- | --- |
| `-p`, `--port <PORT>` | Port to bind to (default `8080`). |
| `--mongo` | Use a MongoDB document store for the repository instead of the default file-based (TAR/segment) store. Requires a running MongoDB instance. |
| `--dev` | Developer mode: also load the [Composum](https://www.composum.com/) repository browser for inspecting the JCR content. |
| `--debug` | Enable Java remote debugging (JDWP) on port `5005`. Startup **pauses until a debugger attaches** — connect with `jdb -attach 5005` (or your IDE). |
| `--test` | Additionally load test content (the `iap-test-forms` feature). |
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
./start.sh --dev           # include the Composum repository browser
./start.sh --debug         # wait for a debugger to attach on port 5005
./start.sh -P myproject    # launch the "iap4myproject" project
```
