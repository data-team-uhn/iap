#!/usr/bin/env python3
#
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
"""Fills a running instance with a year of authorization history, so the metrics have something to say.

The engine does not write the history log yet, and the metrics read nothing else, so this stands in for
it: each generated request gets a plausible life - raised, filled in, sent, reviewed, sometimes argued
with, usually authorized - recorded as `hist:Action` nodes exactly as the engine will record them.

The dates are backdated across a year so that the monthly series and the trend charts have a shape, and
the durations are drawn from distributions rather than fixed, so that a median differs from a mean and
the 45-day figure is neither 0% nor 100%. A deliberate minority is left unfinished, including some that
have already overrun, because those are the cases the service-level metric exists to catch.

Usage, from the repository root:

    python3 tools/dev/test-data/generate-history-data.py [--url http://localhost:8080] [--count 400]
"""

import argparse
import base64
import datetime
import json
import random
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

# The vocabulary the metric definitions under /Statistics are written against. The engine will use the
# domain event names; these are those names.
CREATE = "create"
SUBMIT = "submit"
ISSUE = "issue"
REVIEW = "review"
AUTHORIZE = "authorize"

SUBMISSION_TYPE = "sub/Submission"

REVIEWERS = ["achen", "bpatel", "cmorel", "dsingh"]

REQUESTERS = ["ewong", "fgarcia", "hkovacs", "jokafor", "lnguyen"]

# Two study kinds, so the "by study type" breakdown has something to break down. The first is slower on
# purpose: a breakdown that comes out flat proves nothing about whether the breakdown works.
STUDIES = [
    {"name": "dataStudy", "title": "Retrospective data study", "fields": 14, "speed": 1.0},
    {"name": "chartReview", "title": "Chart review", "fields": 6, "speed": 0.55},
]


class Instance:
    """The running instance, and the few kinds of request this script makes of it."""

    def __init__(self, url, user, password):
        self.url = url.rstrip("/")
        self.auth = base64.b64encode(f"{user}:{password}".encode()).decode()

    def post(self, path, fields):
        body = urllib.parse.urlencode(fields, doseq=True).encode()
        request = urllib.request.Request(f"{self.url}{path}", data=body, method="POST")
        request.add_header("Authorization", f"Basic {self.auth}")
        request.add_header("Content-Type", "application/x-www-form-urlencoded")
        try:
            with urllib.request.urlopen(request) as response:
                return response.status
        except urllib.error.HTTPError as failure:
            print(f"  ! {failure.code} POST {path}", file=sys.stderr)
            print(f"    {failure.read().decode(errors='replace')[:400]}", file=sys.stderr)
            raise

    def get_json(self, path):
        request = urllib.request.Request(f"{self.url}{path}")
        request.add_header("Authorization", f"Basic {self.auth}")
        with urllib.request.urlopen(request) as response:
            return json.load(response)

    def delete(self, path):
        try:
            self.post(path, {":operation": "delete"})
        except urllib.error.HTTPError:
            pass


def iso(moment):
    return moment.strftime("%Y-%m-%dT%H:%M:%S.000") + "+00:00"


def bucket_path(identifier):
    """Where an action is filed: the prefix tree the history store shards on, three levels of two."""
    return f"/History/{identifier[0:2]}/{identifier[2:4]}/{identifier[4:6]}"


def create_schemas(instance):
    """The study kinds the requests answer, and the version of each that they point at."""
    versions = {}
    for study in STUDIES:
        path = f"/Schemas/{study['name']}"
        instance.post(path, {
            "jcr:primaryType": "sch:Schema",
            "title": study["title"],
            "active": "true",
            "active@TypeHint": "Boolean",
        })
        instance.post(f"{path}/v1", {
            "jcr:primaryType": "sch:SchemaVersion",
            "version": "1.0",
            "active": "true",
            "active@TypeHint": "Boolean",
        })
        # The questions an answer points at: sub:Answer's `question` reference is mandatory, so a
        # generated answer needs a real question to be an answer to.
        questions = []
        for field in range(study["fields"] + 4):
            question_path = f"{path}/v1/q{field}"
            instance.post(question_path, {
                "jcr:primaryType": "sch:Question",
                "text": f"Question {field + 1}",
                "dataType": "text",
            })
            questions.append(instance.get_json(f"{question_path}.json")["jcr:uuid"])
        versions[study["name"]] = {
            "version": instance.get_json(f"{path}/v1.json")["jcr:uuid"],
            "questions": questions,
        }
        print(f"  schema {path} with {len(questions)} questions")
    return versions


def record_action(instance, when, actor, operation, subject_uuid, subject_path, outcome=None):
    """One `hist:Action` with its one `hist:Entry`, filed where the history store files them."""
    action_id = uuid.uuid4().hex
    folder = bucket_path(action_id)
    for depth in range(1, 4):
        instance.post("/".join(folder.split("/")[:depth + 1]), {"jcr:primaryType": "hist:Log"})
    action_path = f"{folder}/{action_id}"
    fields = {
        "jcr:primaryType": "hist:Action",
        "actor": actor,
        "operation": operation,
        # When it happened. jcr:created is protected, so a backdated record cannot use it; occurredAt is
        # the property the history store keeps for exactly this, and the metrics prefer it.
        "occurredAt": iso(when),
        "occurredAt@TypeHint": "Date",
        "complete": "true",
        "complete@TypeHint": "Boolean",
    }
    if outcome:
        fields["outcome"] = outcome
    instance.post(action_path, fields)
    instance.post(f"{action_path}/{subject_uuid}", {
        "jcr:primaryType": "hist:Entry",
        "subject": subject_uuid,
        "subjectPath": subject_path,
        "subjectType": SUBMISSION_TYPE,
        "role": "subject",
    })


def life_of_a_request(rng, started, study):
    """A plausible sequence of moments for one request, in the order they happen.

    Returns the offsets in days from creation, and whether the request stalls before being decided.
    A request whose later offsets fall after today simply has not got there yet, which is the honest
    state for a good number of them.
    """
    speed = study["speed"]
    # Long-tailed on purpose. Institutional authorization is a weeks-to-months process, so durations
    # drawn tightly around a few days would make the 45-day figure a flat 100% and prove nothing about
    # whether the metric works.
    fill_in = rng.lognormvariate(2.0, 0.8) * speed
    to_first_issue = rng.lognormvariate(1.6, 0.8)
    issues = rng.choices([0, 1, 2, 3, 5], weights=[35, 30, 20, 10, 5])[0]
    to_approval = to_first_issue + rng.lognormvariate(2.6, 0.9) * speed
    to_authorization = to_approval + rng.lognormvariate(2.2, 1.0)
    # A minority stall after being sent and are never decided. Some of them are already past any
    # reasonable deadline, which is exactly the case the service-level metric has to catch.
    stalls = rng.random() < 0.08
    return fill_in, to_first_issue, issues, to_approval, to_authorization, stalls


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--user", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--count", type=int, default=400)
    parser.add_argument("--seed", type=int, default=20260905)
    parser.add_argument("--keep", action="store_true", help="add to what is there instead of replacing it")
    options = parser.parse_args()

    rng = random.Random(options.seed)
    instance = Instance(options.url, options.user, options.password)
    now = datetime.datetime.now(datetime.timezone.utc)

    if not options.keep:
        print("Clearing the generated history ...")
        instance.delete("/History")
        instance.post("/History", {"jcr:primaryType": "hist:Log"})

    print("Installing the study kinds ...")
    versions = create_schemas(instance)

    print(f"Generating {options.count} requests across the last year ...")
    made = {"created": 0, "submitted": 0, "authorized": 0, "overrunning": 0}
    for index in range(options.count):
        study = rng.choices(STUDIES, weights=[6, 4])[0]
        started = now - datetime.timedelta(days=rng.uniform(0, 365), hours=rng.uniform(0, 24))
        requester = rng.choice(REQUESTERS)
        reviewer = rng.choice(REVIEWERS)
        fill_in, to_first_issue, issues, to_approval, to_authorization, stalls = \
            life_of_a_request(rng, started, study)

        submission_path = f"/Submissions/generated-{index}"
        instance.post(submission_path, {
            "jcr:primaryType": "sub:Submission",
            "title": f"{study['title']} #{index + 1}",
            "schemaVersion": versions[study["name"]]["version"],
            "schemaVersion@TypeHint": "Reference",
        })
        subject_uuid = instance.get_json(f"{submission_path}.json")["jcr:uuid"]

        # The fields a requester filled in. Only how many there are matters to the metrics, but each
        # still has to be a real answer to a real question for the repository to accept it.
        questions = versions[study["name"]]["questions"]
        filled = max(1, min(len(questions), int(rng.gauss(study["fields"], 2))))
        for field in range(filled):
            instance.post(f"{submission_path}/answer{field}", {
                "jcr:primaryType": "sub:Answer",
                "question": questions[field],
                "question@TypeHint": "Reference",
                "value": "given",
            })

        record_action(instance, started, requester, CREATE, subject_uuid, submission_path)
        made["created"] += 1

        submitted_at = started + datetime.timedelta(days=fill_in)
        if submitted_at > now:
            continue
        record_action(instance, submitted_at, requester, SUBMIT, subject_uuid, submission_path)
        made["submitted"] += 1

        for issue in range(issues):
            raised_at = submitted_at + datetime.timedelta(days=to_first_issue + issue * 1.7)
            if raised_at < now:
                record_action(instance, raised_at, reviewer, ISSUE, subject_uuid, submission_path)

        approved_at = submitted_at + datetime.timedelta(days=to_approval)
        if stalls or approved_at > now:
            if (now - started).days > 45:
                made["overrunning"] += 1
            continue
        record_action(instance, approved_at, reviewer, REVIEW, subject_uuid, submission_path, "approved")

        authorized_at = submitted_at + datetime.timedelta(days=to_authorization)
        if authorized_at > now:
            if (now - started).days > 45:
                made["overrunning"] += 1
            continue
        record_action(instance, authorized_at, "authority", AUTHORIZE, subject_uuid, submission_path)
        made["authorized"] += 1

        if (index + 1) % 50 == 0:
            print(f"  {index + 1} / {options.count}")

    print(f"Done: {made['created']} raised, {made['submitted']} sent, {made['authorized']} authorized, "
          f"{made['overrunning']} still open past 45 days")

    # The figures are worked out on a schedule, not per request, so freshly generated history would
    # otherwise not show up until the small hours
    print("Working the metrics out...")
    instance.post("/Statistics.refresh.json", {})
    print(f"Read the metrics at {instance.url}/Statistics.json")


if __name__ == "__main__":
    main()
