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

"""Runs one of this module's Python checks in a virtualenv the build provisions itself.

`mvn -Ptests` runs ruff and pytest, but since Maven does not have a good Python integration, it's not possible to
install the dependencies that Python needs. Invoked directly, a missing dependency fails the whole reactor, reporting
only "No module named ruff". Instead of relying on the system to come with preinstalled libraries or installing them
globally, rely on `venv` to build the right environment, when `venv` is available, or gently decline to build and
simply warn that dependencies are missing.

So the first invocation builds a Python virtual environment (`.venv`) beside this file and installs
requirements-test.txt into it. The checks then run against the versions this module declares rather than whatever
the machine happens to have, which is the reason to prefer the virtualenv even where ruff is already installed.

Provisioning needs two things that a Python 3 install can genuinely lack: `venv`, and some way to
get `pip` into it. Where either is missing, or the machine is offline, the check is skipped with a
line naming what to install, rather than failing the build. Under CI (`$CI` set) that same case is
a hard failure instead: a gate that quietly stops running there is worse than a red build.

    python3 -B run_check.py ruff check src
"""

import hashlib
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
VENV = HERE / ".venv"
REQUIREMENTS = HERE / "requirements-test.txt"
# Which requirements the virtualenv was built from, so that adding one rebuilds it rather than
# failing later on an import the venv predates
STAMP = VENV / ".requirements-sha256"
GET_PIP = "https://bootstrap.pypa.io/get-pip.py"

# A CI runner has a full Python and a network, so a failure to provision there is a real breakage
# rather than an unequipped machine, and skipping would retire the gate without anyone noticing
STRICT = bool(os.environ.get("CI"))


def python():
    """The virtualenv's interpreter, wherever this platform puts it."""
    return VENV / ("Scripts/python.exe" if os.name == "nt" else "bin/python")


def run(*command, **kwargs):
    """Runs a command, returning True if it succeeded."""
    return subprocess.run(command, check=False, **kwargs).returncode == 0


def wanted():
    return hashlib.sha256(REQUIREMENTS.read_bytes()).hexdigest()


def usable():
    """Whether the virtualenv is present and built from the requirements as they stand now."""
    return python().exists() and STAMP.exists() and STAMP.read_text().strip() == wanted()


def has_pip():
    """Whether the virtualenv exists and has pip, which a half-finished provisioning run leaves it
    without -- asked so that the next run rebuilds it rather than failing on the same missing pip."""
    return python().exists() and run(str(python()), "-m", "pip", "--version",
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def create():
    """Builds the virtualenv, with pip in it.

    Plain `venv` bootstraps pip through `ensurepip`, which Debian and Ubuntu package separately and
    routinely leave out; there the module still creates a usable environment under `--without-pip`,
    and pip goes in afterwards from get-pip.py. Trying the plain form first keeps the offline path
    working wherever ensurepip is present.
    """
    # Quiet, because failing here is the ordinary case on Debian and the advice it prints ("install
    # python3-venv", eight lines of it) is not what the reader should act on while the fallback works
    if run(sys.executable, "-m", "venv", str(VENV),
           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL):
        return True
    if not run(sys.executable, "-m", "venv", "--clear", "--without-pip", str(VENV)):
        report("python3 -m venv failed", "install the python3-venv package for this interpreter")
        return False
    bootstrap = VENV / "get-pip.py"
    try:
        with urllib.request.urlopen(GET_PIP, timeout=60) as response:
            bootstrap.write_bytes(response.read())
    except OSError as e:
        report(f"this interpreter has no ensurepip and {GET_PIP} is unreachable ({e})",
               "install the python3-venv and python3-pip packages, or restore network access")
        return False
    return run(str(python()), str(bootstrap), "-q")


def provision():
    """Makes `.venv` exist and match requirements-test.txt. False if it could not be built."""
    if usable():
        return True
    print(f"Provisioning {VENV} from {REQUIREMENTS.name} (one time, ~6s)...", file=sys.stderr)
    if not has_pip() and not create():
        return False
    if not run(str(python()), "-m", "pip", "install", "-q", "-r", str(REQUIREMENTS)):
        report("could not install " + REQUIREMENTS.name, "check network access to PyPI")
        return False
    STAMP.write_text(wanted())
    return True


def report(problem, remedy):
    """Explains a check that did not run, on stderr so that Maven surfaces it."""
    print(f"SKIPPED: {problem}, so this module's Python checks did not run.\n"
          f"         To run them: {remedy}.", file=sys.stderr)


def main():
    if not provision():
        # Exit 0 so that one unequipped machine does not fail a reactor-wide build; under CI the
        # checks are not optional
        return 1 if STRICT else 0
    return subprocess.run([str(python()), "-m", *sys.argv[1:]], check=False, cwd=HERE).returncode


if __name__ == "__main__":
    sys.exit(main())
