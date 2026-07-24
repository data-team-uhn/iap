#!/usr/bin/env python3
# -*- coding: utf-8 -*-

# Copyright 2022-2026 DATA @ UHN. See the NOTICE file
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

"""Starts IAP via the Apache Sling feature launcher.

This is the single, cross-platform implementation of the start process; `start.sh`
(Linux, macOS, WSL) and `start.bat` (Windows) are thin wrappers around it.
Keep platform-specific behavior here, not in the wrappers.
"""

import sys

# Guard before the Python-3-only imports below, so that an outdated interpreter (e.g. a
# `python` that is Python 2) produces a clear message instead of a raw ImportError.
if sys.version_info < (3, 6):
    sys.exit('Python 3.6 or later is required to start IAP, but this is Python %d.%d.'
             % (sys.version_info[0], sys.version_info[1]))

import os
import platform
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

from pathlib import Path

BIND_TESTS = 2
BIND_TEST_SPACING = 30

TERMINAL_NOCOLOR = '\033[0m'
TERMINAL_RED = '\033[0;31m'
TERMINAL_GREEN = '\033[0;32m'
TERMINAL_YELLOW = '\033[0;33m'

JAVA_DEBUGGING_FLAGS = ('-Xdebug -Xnoagent -Djava.compiler=NONE'
                        ' -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005')

ROOT = Path(__file__).resolve().parent

IS_WINDOWS = os.name == 'nt'
IS_MACOS = platform.system() == 'Darwin'
# Matches both WSL1 ("...-Microsoft") and WSL2 ("...-microsoft-standard") kernel releases
IS_WSL = 'microsoft' in platform.release().lower()

HELP = """Usage: ./start.sh [OPTIONS] [-- LAUNCHER_ARGS]     (Linux / macOS / WSL)
       start.bat [OPTIONS] [-- LAUNCHER_ARGS]      (Windows)

Starts IAP via the Apache Sling feature launcher.

Options:
  -p, --port <port>              Port for IAP to bind to (default: 8080).
      --permissions <value>      Permissions scheme to apply when resolving
                                 project features (used together with
                                 `--project`).
  -P, --project <project[,...]>  Launch one or more IAP *projects*. Each `<name>`
                                 resolves to the `iap4<name>` artifact and its
                                 dependency features (the `iap4` prefix is
                                 optional).
      --mongo                    Use a MongoDB document store for the repository
                                 instead of the default file-based (TAR/segment)
                                 store. Requires a running MongoDB instance.
      --debug                    Enable Java remote debugging (JDWP) on port
                                 `5005`. Startup pauses until a debugger
                                 attaches - connect with `jdb -attach 5005` (or
                                 your IDE).
      --test                     Additionally load test content (the
                                 `iap-test-data` feature).
  -h, --help                     Show this help message and exit.

Notes:
  - Any argument not listed above is passed through to the Sling feature
    launcher. For the arguments it accepts, see the Apache Sling Feature
    Launcher documentation:
    https://github.com/apache/sling-org-apache-sling-feature-launcher"""


def banner(color, *lines):
    width = max(len(line) for line in lines)
    horizontal = color + '*' * (width + 8) + TERMINAL_NOCOLOR
    spacer = color + '*' + ' ' * (width + 6) + '*' + TERMINAL_NOCOLOR
    print(horizontal)
    print(spacer)
    for line in lines:
        print(color + '*   ' + line.ljust(width) + '   *' + TERMINAL_NOCOLOR)
    print(spacer)
    print(horizontal)


def handle_iap_java_fail(bind_port):
    banner(TERMINAL_RED, 'The IAP Java process has failed at port %d' % bind_port)
    sys.exit(1)


def handle_tcp_bind_fail(bind_port):
    banner(TERMINAL_RED, 'Unable to bind to TCP port %d' % bind_port)
    sys.exit(1)


def get_platform_version():
    with open(str(ROOT / 'pom.xml'), encoding='utf-8') as pom:
        for line in pom:
            if '<version>' in line:
                return line.split('>', 1)[1].split('<', 1)[0]
    sys.exit('Unable to determine PLATFORM_VERSION from pom.xml')


def psutil_usable():
    """The robust bind test needs psutil, which does not work correctly on WSL and macOS."""
    if IS_WSL or IS_MACOS:
        return False
    try:
        import psutil  # noqa: F401 pylint: disable=unused-import
        return True
    except ImportError:
        return False


def port_available(port):
    """Simple bind test: check that the port is available right now.

    Probing the loopback interface is enough to detect a conflicting local server,
    without ever opening a socket reachable from other machines.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        # On WSL and Windows, SO_REUSEADDR would allow rebinding a port that is actually in use
        if not (IS_WSL or IS_WINDOWS):
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(('127.0.0.1', port))
        sock.listen()
        return True
    except OSError:
        return False
    finally:
        sock.close()


def is_listening(port, pid):
    """Robust bind test: check that the process (or a descendant) is listening on the port.

    Descendants matter on Windows, where launcher.bat runs Java as a child process;
    on Linux the shell launcher exec()s Java, keeping the same pid.
    """
    import psutil
    try:
        pids = {pid} | {child.pid for child in psutil.Process(pid).children(recursive=True)}
    except psutil.NoSuchProcess:
        return False
    for conn in psutil.net_connections(kind='tcp'):
        if conn.status == psutil.CONN_LISTEN and conn.pid in pids and conn.laddr.port == port:
            return True
    return False


def get_error_log_last_modified():
    try:
        return os.path.getmtime(str(ROOT / '.iap-data' / 'logs' / 'error.log'))
    except OSError:
        return 0.0


def require_value(argv, i, name):
    if i >= len(argv):
        sys.exit('Missing value for the %s option' % name)
    return argv[i]


def parse_args(argv):
    options = {
        'bind_port': 8080,
        'permissions': '',
        'projects': [],
        'storage': 'tar',
        'debug': False,
        'test': False,
        'passthrough': [],
    }
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg in ('-h', '--help'):
            print(HELP)
            sys.exit(0)
        elif arg in ('-p', '--port'):
            i += 1
            value = require_value(argv, i, arg)
            try:
                options['bind_port'] = int(value)
            except ValueError:
                sys.exit('Invalid port: %s' % value)
        elif arg == '--permissions':
            i += 1
            options['permissions'] = require_value(argv, i, arg)
        elif arg in ('-P', '--project'):
            i += 1
            options['projects'] += require_value(argv, i, arg).split(',')
        elif arg == '--mongo':
            options['storage'] = 'mongo'
        elif arg == '--debug':
            options['debug'] = True
        elif arg == '--test':
            options['test'] = True
        elif arg == '--':
            options['passthrough'] += argv[i + 1:]
            break
        else:
            options['passthrough'].append(arg)
        i += 1
    return options


def get_dependency_features(env, features_file):
    helper = str(ROOT / 'tools' / 'Startup' / 'get_project_dependency_features.py')
    result = subprocess.run([sys.executable, helper, str(features_file)], env=env,
                            stdout=subprocess.PIPE, universal_newlines=True, check=False)
    return [feature for feature in result.stdout.strip().split(',') if feature]


def resolve_project_features(projects, platform_version, project_version, permissions):
    mvn = shutil.which('mvn')
    if mvn is None:
        sys.exit('Maven (mvn) is required to resolve --project features, but was not found on the PATH')
    features = []
    for project in projects:
        # Support both "iap4project" and just "project"
        name = project[len('iap4'):] if project.startswith('iap4') else project
        project = 'iap4' + name
        env = dict(os.environ, PLATFORM_VERSION=platform_version, PROJECT_NAME=project,
                   PROJECT_VERSION=project_version, PERMISSIONS=permissions)
        features.append('mvn:io.uhndata.iap/%s/%s/slingosgifeature' % (project, project_version))
        features += get_dependency_features(env, ROOT / 'tools' / 'Startup' / 'core-sling-features.json')
        dependency_dir = tempfile.mkdtemp()
        try:
            subprocess.run([mvn, '--quiet', '--non-recursive', 'dependency:copy',
                            '-Dartifact=io.uhndata.iap:%s-docker-packaging:%s:dependencies'
                            % (name, project_version),
                            '-DoutputDirectory=%s' % dependency_dir],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            dependencies_file = os.path.join(dependency_dir,
                                             '%s-docker-packaging-%s.dependencies' % (name, project_version))
            if os.path.isfile(dependencies_file):
                features += get_dependency_features(env, dependencies_file)
        finally:
            shutil.rmtree(dependency_dir, ignore_errors=True)
    return features


def is_ready(url):
    try:
        with urllib.request.urlopen(url, timeout=5):
            return True
    except (urllib.error.URLError, OSError):
        return False


def stop(process, interrupted=False):
    """Stop the launcher; `interrupted` means it already received the console Ctrl+C."""
    if process.poll() is not None:
        return
    if IS_WINDOWS:
        if interrupted:
            # Ctrl+C was delivered to the whole console process group, Java included, so
            # let it shut the repository down cleanly before resorting to a force kill.
            try:
                process.wait(timeout=30)
                return
            except subprocess.TimeoutExpired:
                pass
        # launcher.bat runs Java as a child process, so take down the whole process tree
        subprocess.run(['taskkill', '/T', '/F', '/PID', str(process.pid)],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    else:
        process.terminate()
    process.wait()


def monitor_startup(process, bind_port, use_psutil, debug, error_log_time_origin):
    if debug:
        banner(TERMINAL_YELLOW,
               'Please connect JDB to localhost:5005 to continue with startup.',
               'jdb -attach 5005')
        # As soon as we see IAP writing to .iap-data/logs/error.log, we
        # can conclude that JDB has attached to the Java process.
        while get_error_log_last_modified() <= error_log_time_origin:
            time.sleep(5)
            print('Waiting for JDB attachment...')

    # Check to see if IAP was able to bind to the TCP port
    # This is the more robust test that works only if psutil is usable
    if use_psutil:
        for bind_test in range(BIND_TESTS + 1):
            if bind_test == BIND_TESTS:
                stop(process)
                handle_tcp_bind_fail(bind_port)
            time.sleep(BIND_TEST_SPACING)
            if is_listening(bind_port, process.pid):
                break
            # If the IAP Java process has terminated, stop this script altogether
            if process.poll() is not None:
                handle_iap_java_fail(bind_port)
        banner(TERMINAL_GREEN, 'IAP Socket BIND: OK')
    else:
        banner(TERMINAL_YELLOW, 'IAP Socket BIND: OK - used suboptimal bind test')

    url = 'http://localhost:%d/system/sling/info.sessionInfo.json' % bind_port
    while True:
        print('Waiting for IAP to start')
        # If the IAP Java process has terminated, stop this script altogether
        if process.poll() is not None:
            handle_iap_java_fail(bind_port)
        if is_ready(url):
            break
        time.sleep(5)

    banner(TERMINAL_GREEN, 'Started IAP at port %d' % bind_port)


def main(argv):
    if IS_WINDOWS:
        os.system('')  # enables ANSI color code processing in the Windows console

    options = parse_args(argv)
    bind_port = options['bind_port']

    platform_version = get_platform_version()
    print('PLATFORM_VERSION', platform_version)
    project_version = os.environ.get('PROJECT_VERSION') or platform_version

    use_psutil = psutil_usable()
    # Without psutil, simply check that the port is available now,
    # and therefore will likely be available in the very near future
    if not use_psutil and not port_available(bind_port):
        handle_tcp_bind_fail(bind_port)

    # Allow referring to the current version with the literal token `VERSION` in launcher arguments
    launcher_args = [arg.replace('VERSION', platform_version) for arg in options['passthrough']]
    if options['test']:
        launcher_args += ['-f', 'mvn:io.uhndata.iap/iap-test-data/%s/slingosgifeature' % platform_version]
    if options['projects']:
        features = resolve_project_features(options['projects'], platform_version, project_version,
                                            options['permissions'])
        launcher_args += ['-f', ','.join(features)]

    launcher = (ROOT / 'packaging' / 'target' / 'dependency' / 'org.apache.sling.feature.launcher'
                / 'bin' / ('launcher.bat' if IS_WINDOWS else 'launcher'))
    if not launcher.is_file():
        sys.exit('The Sling feature launcher is missing at %s - run `mvn install` first' % launcher)

    java_opts = '-Djdk.xml.entityExpansionLimit=0 -Dorg.osgi.service.http.port=%d' % bind_port
    if options['debug']:
        java_opts = JAVA_DEBUGGING_FLAGS + ' ' + java_opts
    env = dict(os.environ, JAVA_OPTS=java_opts)

    # Path.as_uri() produces the platform-correct form (file:///home/... or file:///C:/...)
    repository_urls = ','.join([
        (ROOT / '.mvnrepo').as_uri(),
        (Path.home() / '.m2' / 'repository').as_uri(),
        'https://repo.maven.apache.org/maven2',
    ])

    error_log_time_origin = get_error_log_last_modified()

    command = [str(launcher),
               '-u', repository_urls,
               '-p', '.iap-data',
               '-c', '.iap-data/cache',
               '-f', 'mvn:io.uhndata.iap/iap-packaging-slingfeature/%s/slingosgifeature/core_%s'
               % (platform_version, options['storage'])]
    command += launcher_args
    process = subprocess.Popen(command, env=env, cwd=str(ROOT))

    try:
        monitor_startup(process, bind_port, use_psutil, options['debug'], error_log_time_origin)
        # Stop this script if the IAP process terminates
        process.wait()
    except KeyboardInterrupt:
        print('Shutting down IAP')
        stop(process, interrupted=True)
        return 0
    if process.returncode != 0:
        handle_iap_java_fail(bind_port)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
