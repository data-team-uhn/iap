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
#

"""Delivering parse results back to the caller's callback endpoint.

When ``/parse`` is called with ``job_id`` and ``callback`` parameters, the daemon answers
immediately and converts in the background; once done, it POSTs the outcome as JSON to the
callback URL. The callback endpoint authenticates the daemon with a shared JWT, handed to
both sides as the ``IAP_DOCLING_CALLBACK_JWT`` environment variable and sent here as a
bearer token. The token is opaque to this module: it is attached, never parsed.

Kept free of any Docling import so it stays unit-testable without the heavy dependencies.
"""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from typing import Any

TOKEN_ENVIRONMENT_VARIABLE = "IAP_DOCLING_CALLBACK_JWT"

DELIVERY_ATTEMPTS = 3
RETRY_DELAY_SECONDS = 5.0
DELIVERY_TIMEOUT_SECONDS = 30.0


def callback_token() -> str | None:
    """The shared callback JWT, or ``None`` when not configured."""
    token = (os.environ.get(TOKEN_ENVIRONMENT_VARIABLE) or "").strip()
    return token or None


def success_payload(job_id: str, summary: dict[str, Any]) -> dict[str, Any]:
    """The callback body for a finished parse.

    The conversion summary is passed through minus ``logs``: the per-batch diagnostics are
    already echoed to the container log, and can be arbitrarily large.

    @param job_id: the caller's job identifier, echoed back verbatim
    @param summary: what ``parse_document`` returned
    @return: the JSON-serializable callback body
    """
    payload = {key: value for key, value in summary.items() if key != "logs"}
    payload["job_id"] = job_id
    return payload


def failure_payload(job_id: str, error: str) -> dict[str, Any]:
    """The callback body for a parse that could not be completed.

    @param job_id: the caller's job identifier, echoed back verbatim
    @param error: what went wrong
    @return: the JSON-serializable callback body
    """
    return {"job_id": job_id, "ok": False, "error": error}


def deliver(
    callback_url: str,
    payload: dict[str, Any],
    *,
    token: str,
    attempts: int = DELIVERY_ATTEMPTS,
    retry_delay: float = RETRY_DELAY_SECONDS,
    timeout: float = DELIVERY_TIMEOUT_SECONDS,
    log: Callable[[str], None] = print,
) -> bool:
    """POST a callback body to the caller, retrying a few times before giving up.

    A short outage of the caller (a redeploy, a restart) should not lose the result of an
    expensive parse, so failed deliveries are retried; a caller that stays away longer than
    the retries is on its own, and the loss is logged. Every 2xx answer counts as delivered.

    @param callback_url: where to POST
    @param payload: the JSON-serializable callback body
    @param token: the shared callback JWT, sent as a bearer token
    @param attempts: how many deliveries to try in total
    @param retry_delay: seconds to wait between attempts
    @param timeout: seconds to wait for each answer
    @param log: line logger for delivery failures
    @return: whether the callback was accepted
    """
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    job_id = payload.get("job_id")
    for attempt in range(1, attempts + 1):
        request = urllib.request.Request(
            callback_url,
            data=body,
            headers={
                "Content-Type": "application/json; charset=utf-8",
                "Authorization": f"Bearer {token}",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                # urlopen only returns for 2xx answers; anything else raises HTTPError
                log(f"Callback for job {job_id} delivered with HTTP {response.status}")
                return True
        except urllib.error.HTTPError as refusal:
            # The caller answered and said no. A missing job or a bad token will not fix
            # itself between retries, but retrying is still safer than assuming so: the
            # caller may just be mid-startup, answering errors while it wires itself up.
            log(
                f"Callback for job {job_id} refused with HTTP {refusal.code}"
                f" (attempt {attempt}/{attempts})"
            )
        except OSError as failure:
            # Connection refused, DNS failure, timeout: the caller is not there yet
            log(f"Callback for job {job_id} failed: {failure} (attempt {attempt}/{attempts})")
        if attempt < attempts:
            time.sleep(retry_delay)
    log(f"Callback for job {job_id} abandoned after {attempts} attempts")
    return False
