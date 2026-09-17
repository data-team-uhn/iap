#!/usr/bin/env python3
# -*- coding: utf-8 -*-

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

"""Measures how many calls the LLM provider handles at once before each one slows down.

Fires K identical chat requests at the same moment, for each K asked for, and reports how long each
call took and how long the whole wave took. The number to put in the LLM module's `maxCallsInFlight`
is the largest K where the time per call (wave time / K) is still clearly falling. Past that, adding
calls only makes every one of them slower.

The request is shaped like a Step 2 extraction call: a system prompt, a question, and about 20,000
tokens of document. Pass a real parsed protocol with --document; without one, filler text of the
same size is used.

    PROMPTER_API_KEY=... python3 bench_prompter.py --model GPT-OSS-120B --document proposal.md
    python3 bench_prompter.py --dry-run

Nothing here talks to IAP. It talks to the provider the way OpenAIClient does: same endpoint, same
body fields, same project id.
"""

import argparse
import json
import os
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request

DEFAULT_ENDPOINT = "https://prompter.uhndata.io/api/proxy/v1"
DEFAULT_MODEL = "GPT-OSS-120B"
DEFAULT_PROJECT_ID = "ia45-data"
DEFAULT_API_KEY_VARIABLE = "PROMPTER_API_KEY"
DEFAULT_WAVES = "1,2,4,6,8,12"
DEFAULT_PROMPT_TOKENS = 20000
DEFAULT_MAX_OUTPUT_TOKENS = 2000
DEFAULT_TIMEOUT_SECONDS = 300

# The same rough estimate the Java side uses when it packs chunks into a call
CHARS_PER_TOKEN = 4

# A wave has to be this much faster per call than the one before it to count as an improvement
IMPROVEMENT_FLOOR = 0.9

SYSTEM_PROMPT = (
    "You read research protocols and answer questions about them. Every answer carries a verbatim "
    "quote from the text. Answer as JSON with one key per question."
)
QUESTION = (
    "What are the study title, the primary objective and the planned sample size? "
    "Give each with the quote it comes from."
)
FILLER = (
    "## Study design\n\nThis is a prospective, multicentre, randomised trial enrolling adults with type 2 "
    "diabetes at three sites. Participants are followed for 52 weeks. The primary outcome is the change in "
    "HbA1c from baseline to week 52, analysed with a mixed model for repeated measures.\n\n"
)


def parse_arguments(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--endpoint", default=DEFAULT_ENDPOINT, help="OpenAI-compatible base URL")
    parser.add_argument("--model", default=DEFAULT_MODEL, help="the model name the provider knows")
    parser.add_argument("--project-id", default=DEFAULT_PROJECT_ID, help="sent as project_id; empty to omit")
    parser.add_argument("--api-key-variable", default=DEFAULT_API_KEY_VARIABLE,
                        help="the environment variable holding the bearer token")
    parser.add_argument("--waves", default=DEFAULT_WAVES, help="wave sizes to try, comma separated")
    parser.add_argument("--prompt-tokens", type=int, default=DEFAULT_PROMPT_TOKENS,
                        help="how much document text each call carries")
    parser.add_argument("--max-output-tokens", type=int, default=DEFAULT_MAX_OUTPUT_TOKENS)
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_SECONDS, help="per call, in seconds")
    parser.add_argument("--document", help="a parsed protocol (.md) to send instead of filler text")
    parser.add_argument("--json", dest="json_path", help="also write every measurement here")
    parser.add_argument("--dry-run", action="store_true", help="show what would be sent and stop")
    return parser.parse_args(argv)


def get_wave_sizes(waves):
    sizes = sorted({int(size) for size in waves.split(",") if size.strip()})
    if not sizes or sizes[0] < 1:
        raise SystemExit("--waves needs positive numbers, for example 1,2,4,8")
    return sizes


def get_document(path, prompt_tokens):
    wanted = prompt_tokens * CHARS_PER_TOKEN
    if path:
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
    else:
        text = FILLER * (wanted // len(FILLER) + 1)
    return text[:wanted]


def build_body(model, project_id, document, max_output_tokens):
    body = {
        "model": model,
        "temperature": 0,
        "max_tokens": max_output_tokens,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": QUESTION + "\n\n" + document},
        ],
        "chat_template_kwargs": {"enable_thinking": False},
    }
    if project_id:
        body["project_id"] = project_id
    return body


def build_headers(api_key):
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = "Bearer " + api_key
    return headers


def send_once(url, headers, payload, timeout):
    """One call. Returns (seconds, error), where error is None when the call came back with a 200."""
    started = time.monotonic()
    request = urllib.request.Request(url, data=payload, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            response.read()
        return time.monotonic() - started, None
    except urllib.error.HTTPError as error:
        return time.monotonic() - started, "HTTP %d" % error.code
    except Exception as error:  # pylint: disable=broad-except
        return time.monotonic() - started, type(error).__name__ + ": " + str(error)


def run_wave(size, url, headers, payload, timeout):
    """Fires `size` calls at once. Returns (wall seconds, list of (seconds, error))."""
    results = [None] * size

    def call(index):
        results[index] = send_once(url, headers, payload, timeout)

    threads = [threading.Thread(target=call, args=(index,), daemon=True) for index in range(size)]
    started = time.monotonic()
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return time.monotonic() - started, results


def summarize_wave(size, wall, results):
    latencies = [seconds for seconds, error in results if error is None]
    errors = [error for _, error in results if error is not None]
    return {
        "calls": size,
        "ok": len(latencies),
        "failed": len(errors),
        "wall_s": round(wall, 2),
        "per_call_s": round(wall / size, 2),
        "median_s": round(statistics.median(latencies), 2) if latencies else None,
        "max_s": round(max(latencies), 2) if latencies else None,
        "errors": errors,
    }


def pick_cap(summaries):
    """The largest wave that was still clearly cheaper per call than the wave before it, with no failures."""
    cap = None
    previous = None
    for summary in summaries:
        if summary["failed"]:
            break
        if previous is None or summary["per_call_s"] < previous * IMPROVEMENT_FLOOR:
            cap = summary["calls"]
            previous = summary["per_call_s"]
        else:
            break
    return cap


def print_table(summaries):
    print()
    print("%6s %4s %6s %9s %11s %9s %8s" % ("calls", "ok", "failed", "wall (s)", "per call(s)", "median(s)", "max(s)"))
    for summary in summaries:
        print("%6d %4d %6d %9s %11s %9s %8s" % (
            summary["calls"], summary["ok"], summary["failed"], summary["wall_s"], summary["per_call_s"],
            summary["median_s"] if summary["median_s"] is not None else "-",
            summary["max_s"] if summary["max_s"] is not None else "-"))
        for error in summary["errors"]:
            print("       ! " + error)


def main(argv=None):
    arguments = parse_arguments(argv)
    sizes = get_wave_sizes(arguments.waves)
    document = get_document(arguments.document, arguments.prompt_tokens)
    body = build_body(arguments.model, arguments.project_id, document, arguments.max_output_tokens)
    payload = json.dumps(body).encode("utf-8")
    url = arguments.endpoint.rstrip("/") + "/chat/completions"
    api_key = os.environ.get(arguments.api_key_variable, "")

    print("endpoint : " + url)
    print("model    : " + arguments.model)
    print("waves    : " + ", ".join(str(size) for size in sizes))
    print("prompt   : about %d tokens (%d bytes), up to %d output tokens"
          % (len(document) // CHARS_PER_TOKEN, len(payload), arguments.max_output_tokens))
    print("api key  : " + ("set" if api_key else "NOT set (" + arguments.api_key_variable + ")"))
    if arguments.dry_run:
        return 0

    headers = build_headers(api_key)
    summaries = []
    for size in sizes:
        print("\nwave of %d ..." % size, end="", flush=True)
        wall, results = run_wave(size, url, headers, payload, arguments.timeout)
        summary = summarize_wave(size, wall, results)
        summaries.append(summary)
        print(" %ss wall, %ss per call, %d failed" % (summary["wall_s"], summary["per_call_s"], summary["failed"]))

    print_table(summaries)
    cap = pick_cap(summaries)
    if cap is None:
        print("\nNo wave finished cleanly. Nothing to suggest.")
    else:
        print("\nSuggested maxCallsInFlight: %d (the largest wave still clearly cheaper per call)" % cap)

    if arguments.json_path:
        with open(arguments.json_path, "w", encoding="utf-8") as handle:
            json.dump({"endpoint": url, "model": arguments.model, "prompt_tokens": arguments.prompt_tokens,
                       "max_output_tokens": arguments.max_output_tokens, "waves": summaries,
                       "suggested_max_calls_in_flight": cap}, handle, indent=2)
        print("written " + arguments.json_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
