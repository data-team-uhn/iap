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
"""Stopping one parse without shutting the daemon down."""

import threading
from concurrent.futures import Future

from docling_daemon import DaemonState


def _state() -> DaemonState:
    # Skip DaemonState.__init__: it warms the PDF pool, and cancel only needs the bookkeeping.
    state = DaemonState.__new__(DaemonState)
    state.pending_lock = threading.Lock()
    state.pending_parses = {}
    state.cancelled_jobs = set()
    return state


def test_a_queued_parse_is_cancelled():
    state = _state()
    future: Future = Future()
    state.pending_parses[future] = ("job-1", "http://callback", "token")

    assert state.cancel_parse("job-1") == "cancelled"
    assert future.cancelled()
    assert state.pending_parses == {}


def test_a_running_parse_is_remembered_so_it_does_not_call_back():
    state = _state()
    future: Future = Future()
    future.set_result(None)
    state.pending_parses[future] = ("job-1", "http://callback", "token")

    assert state.cancel_parse("job-1") == "running"
    assert state.is_cancelled("job-1")


def test_an_unknown_job_is_not_ours():
    assert _state().cancel_parse("missing") == "unknown"
