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

"""Files the pretotype's studies on a running instance. PRETOTYPE.

What a study team does goes through the real workflow, as `researcher`: creating the submission, answering,
attaching documents, sending it. What the platform has no way to make yet (the AI's extractions and pre-review,
open reviews, the review clock running for days) is written as admin, with the Sling POST servlet.

    python3 demos/institutional-review/tools/seed.py --url http://localhost:8083
"""

import argparse
import datetime
import html
import json
import pathlib
import re
import subprocess
import sys
import tempfile

import requests

SCHEMA = "/Schemas/retrospectiveStudy/v1"
ASSISTANT = "iap-assistant"
CHROME = pathlib.Path.home() / ".cache/ms-playwright/chromium-1248/chrome-linux64/chrome"

# The REB's issues on the study it sent back for changes, written as its reviewer would
SENT_BACK = {
    "NCT06821139": [
        {"text": "The form says the data arrives anonymized with no key, but the protocol links the anesthesia "
                 "records to the discharge abstracts on name, date of birth, MRN and PHN, and the PI keeps the "
                 "linking file. That is coded data with the key in the team. Please correct the identifiability "
                 "answers and request a waiver of consent with its justification.",
         "severity": "major", "kind": "conflict",
         "subjects": ["identifiability/arrival", "identifiability/keyHolder", "consent/sought"]},
        {"text": "Consent is marked not applicable on the strength of TCPS 2 Article 3.7A. That article does not "
                 "apply to secondary use of identifiable information; Article 5.5A does.",
         "severity": "medium", "kind": "conflict", "subjects": ["consent/sought"]},
        {"text": "Please say who receives the linked dataset, where it is analyzed, and when the linking file is "
                 "destroyed.", "severity": "minor", "kind": "gap",
         "subjects": ["handling/storage/location", "handling/storage/retention"]},
    ],
}

# What reviewers already wrote on studies they started on: notes to self, and the conversations they used to have
# by email. (author, text, replies)
ACTIVITY = {
    "NCT04088747": {
        "notes": {"reb-reviewer": ["Waiting on Digital about the classifier before I write anything up."]},
        "discussion": [("reb-reviewer", "Priya, the form says no AI, but the whole study is an elastic-net classifier "
                        "trained on the ultrasound images. Does Digital need to see it?",
                        [("digital-reviewer", "Yes, that is model development on imaging. I have flagged it and will "
                          "ask them for the AI Hub intake.")])],
    },
    "NCT07570901": {
        "discussion": [("reb-reviewer-2", "The overlap answer mentions a combined paper with the Jena group. If any "
                        "record-level data goes to Germany this needs a DSA and Privacy. Has anyone asked the team?",
                        [])],
    },
    "NCT06821139": {
        "notes": {"reb-reviewer": ["Sent back for the linkage and waiver. Check how the PHN is handled when it "
                                   "comes back."]},
    },
    "NCT05018104": {
        "notes": {"reb-reviewer": ["The waiver rests on coordinator time only. Ask for a real impracticability "
                                   "argument."]},
    },
    "NCT07430618": {
        "discussion": [("digital-reviewer", "Key to Health's backend runs in their own cloud. I need the hosting "
                        "region before Digital can sign off.",
                        [("reb-reviewer-2", "They are also evaluating their own product, and no conflict is declared. "
                          "I will raise it on our side.")])],
    },
}

# How long ago each study was sent, in days, and anything else about where it stands. The order of the queue
# (by deadline) follows from these.
STAGES = {
    "NCT07570901": {"age": 9},
    "NCT05188001": {"age": 6},
    "NCT07430618": {"age": 3, "fastTrack": True},
    "NCT07351084": {"age": 12},
    "NCT04120324": {"age": 2},
    "NCT06821139": {"age": 11, "paused": 4},
    "NCT04088747": {"age": 17},
    "NCT05018104": {"age": 14},
    "NCT05150548": {"age": 8},
    "NCT07162909": {"age": 5},
    "NCT05735561": {"age": 1, "fastTrack": True},
    "NCT06239441": {"age": 13},
    "NCT06638242": {"age": 7},
    "NCT06561217": {"age": 4},
}


class Instance:
    def __init__(self, url, user):
        self.url = url.rstrip("/")
        self.session = requests.Session()
        response = self.session.post(f"{self.url}/j_security_check",
                                     data={"j_username": user, "j_password": user, "j_validate": "true"})
        response.raise_for_status()

    def get(self, path):
        response = self.session.get(f"{self.url}{path}")
        response.raise_for_status()
        return response.json()

    def post(self, path, data=None, files=None, expect=(200, 201, 302)):
        response = self.session.post(f"{self.url}{path}", data=data, files=files, allow_redirects=False)
        if response.status_code not in expect:
            raise RuntimeError(f"POST {path}: {response.status_code} {response.text[:500]}")
        return response

    def delete(self, path):
        self.session.delete(f"{self.url}{path}", params={"permanent": "true"})


def form(fields):
    """Sling POST fields, as a list of pairs so that repeated names make multi-valued properties."""
    pairs = []
    for name, value in fields.items():
        for one in value if isinstance(value, list) else [value]:
            pairs.append((name, one))
    return pairs


def iso(when):
    return when.isoformat(timespec="milliseconds")


def pdf_from_html(body, title, out):
    page = f"""<!doctype html><html><head><meta charset="utf-8"><title>{html.escape(title)}</title><style>
      body {{ font: 11pt/1.4 "Liberation Sans", Arial, sans-serif; margin: 2cm; color: #111; }}
      h1 {{ font-size: 16pt; }} table {{ border-collapse: collapse; width: 100%; }}
      th, td {{ border: 1px solid #999; padding: 4pt 6pt; text-align: left; vertical-align: top; }}
      th {{ background: #eee; }} .note {{ color: #555; font-size: 9pt; margin-top: 1.5em; }}
    </style></head><body>{body}</body></html>"""
    with tempfile.NamedTemporaryFile("w", suffix=".html", delete=False) as source:
        source.write(page)
    subprocess.run([str(CHROME), "--headless", "--disable-gpu", "--no-pdf-header-footer",
                    f"--print-to-pdf={out}", source.name], check=True, capture_output=True)


def variable_list_pdf(study, out):
    rows = "".join(
        f"<tr><td>{html.escape(v['field'])}</td><td>{html.escape(v.get('source', ''))}</td>"
        f"<td>{'Yes' if v.get('identifying') else 'No'}</td><td>{html.escape(v.get('why', ''))}</td></tr>"
        for v in study["variableList"])
    body = (f"<h1>Variable list</h1><p><b>{html.escape(study['title'])}</b><br>"
            f"Principal investigator: {html.escape(study['answers'].get('study/pi', ''))}</p>"
            "<table><tr><th>Field</th><th>Source</th><th>Identifying</th><th>Why it is needed</th></tr>"
            f"{rows}</table>")
    pdf_from_html(body, "Variable list", out)


def protocol_file(content, nct):
    files = sorted((content / "protocols" / nct).glob("*Prot*.pdf"))
    return files[0]


def answer_payload(answers):
    payload = []
    for path, value in answers.items():
        values = value if isinstance(value, list) else [value]
        for one in values:
            payload.append((path, str(one).lower() if isinstance(one, bool) else str(one)))
    return payload


def file_study(researcher, admin, content, study):
    created = researcher.post("/Submissions.json", {"title": study["title"], "schemaVersion": SCHEMA}).json()
    path = created["path"]
    researcher.post(f"{path}.save.json", answer_payload(study["answers"]))
    with open(protocol_file(content, study["nct"]), "rb") as protocol:
        researcher.post(f"{path}.attachDocument.json", {"requirement": "protocol"},
                        files={"file": (f"{study['nct']} protocol.pdf", protocol, "application/pdf")})
    with tempfile.TemporaryDirectory() as scratch:
        variables = pathlib.Path(scratch) / "Variable list.pdf"
        variable_list_pdf(study, variables)
        with open(variables, "rb") as listing:
            researcher.post(f"{path}.attachDocument.json", {"requirement": "variables"},
                            files={"file": ("Variable list.pdf", listing, "application/pdf")})
    return path


def open_task(admin, path, element):
    instances = admin.get(f"{path}/wf:instances.2.json")
    for name, instance in instances.items():
        task = instance.get(element) if isinstance(instance, dict) else None
        if isinstance(task, dict) and task.get("jcr:primaryType") == "wf:TaskInstance" \
                and task.get("status") in ("created", "active"):
            return f"{path}/wf:instances/{name}/{element}"
    raise RuntimeError(f"No open {element} task on {path}")


def send(researcher, admin, path):
    researcher.post(open_task(admin, path, "fillIn") + ".json", {})


def uuid(admin, path):
    return admin.get(f"{path}.json")["jcr:uuid"]


def children(admin, path, resource_type):
    return {name: node for name, node in admin.get(f"{path}.1.json").items()
            if isinstance(node, dict) and node.get("sling:resourceType") == resource_type}


def create(admin, path, fields):
    admin.post(path, form(fields))


def schema_parts(admin):
    """Paths, relative to the schema version, of its questions, requirements and topics, to their UUIDs."""
    out = {}

    def walk(node, prefix):
        for name, child in node.items():
            if not isinstance(child, dict):
                continue
            here = f"{prefix}/{name}" if prefix else name
            if "jcr:uuid" in child:
                out[here] = child["jcr:uuid"]
            walk(child, here)
    walk(admin.get(f"{SCHEMA}.infinity.json"), "")
    return out


def ref(value):
    """A reference as the serialization gives it, a UUID, a path or the node it points at, as a schema part."""
    if isinstance(value, dict):
        value = value.get("@path", "")
    return value[len(SCHEMA) + 1:] if isinstance(value, str) and value.startswith(SCHEMA + "/") else value


def submission_parts(admin, path, schema):
    by_uuid = {uuid: part for part, uuid in schema.items()}
    by_uuid.update({part: part for part in schema})
    answers = {by_uuid.get(ref(node["question"])): (f"{path}/{name}", node["jcr:uuid"])
               for name, node in children(admin, path, "sub/Answer").items()}
    documents = {}
    for name, node in children(admin, path, "sub/Document").items():
        versions = children(admin, f"{path}/{name}", "sub/DocumentVersion")
        if versions:
            latest = sorted(versions.items(), key=lambda kv: kv[1]["jcr:created"])[-1][1]
            documents[by_uuid.get(ref(node["fulfills"]))] = (node["jcr:uuid"], latest["jcr:uuid"])
    return answers, documents


def context_fields(source, quote, page):
    fields = {"jcr:primaryType": "sub:Context", "source": source, "source@TypeHint": "WeakReference",
              "quote": quote}
    if page:
        fields.update({"page": str(page), "page@TypeHint": "Long"})
    return fields


def as_text(value):
    values = value if isinstance(value, list) else [value]
    return "; ".join(str(v).lower() if isinstance(v, bool) else str(v) for v in values)


def add_extractions(admin, study, answers, documents):
    protocol = documents["protocol"][1]
    for index, extraction in enumerate(study.get("extractions", [])):
        answer = answers.get(extraction["question"])
        if not answer:
            continue
        node = f"{answer[0]}/extraction{index}"
        create(admin, node, {
            "jcr:primaryType": "sub:Extraction", "sources": protocol, "sources@TypeHint": "Reference[]",
            "extractedAnswer": as_text(extraction["extractedAnswer"]), "reasoning": extraction["reasoning"],
            "confidence": str(extraction["confidence"]), "confidence@TypeHint": "Double"})
        create(admin, f"{node}/context", context_fields(protocol, extraction["quote"], extraction["page"]))


def add_comment(admin, node, comment, author, answers, documents, tags=None):
    subjects = [answers[s][1] if s in answers else documents[s][0]
                for s in comment.get("subjects", []) if s in answers or s in documents]
    fields = {"jcr:primaryType": "sub:Comment", "text": comment["text"], "author": author,
              "kind": comment.get("kind", ""), "tags": tags or [comment["severity"]], "tags@TypeHint": "String[]"}
    if comment.get("suggestion"):
        fields["suggestion"] = comment["suggestion"]
    if subjects:
        fields.update({"subjects": subjects, "subjects@TypeHint": "WeakReference[]"})
    create(admin, node, fields)
    if comment.get("quote") and "protocol" in documents:
        create(admin, f"{node}/context", context_fields(documents["protocol"][1], comment["quote"], comment.get("page")))


def add_screening(admin, path, study, schema, answers, documents):
    screening = study["screening"]
    node = f"{path}/screening"
    create(admin, node, {"jcr:primaryType": "sub:Screening", "summary": screening["summary"]})
    for assessment in screening["assessments"]:
        topic = schema[f"{assessment['approval']}/{assessment['topic']}"]
        here = f"{node}/{assessment['approval']}-{assessment['topic']}"
        create(admin, here, {"jcr:primaryType": "sub:Assessment", "topic": topic, "topic@TypeHint": "Reference",
                             "summary": assessment["summary"]})
        for index, comment in enumerate(assessment["comments"]):
            add_comment(admin, f"{here}/comment{index}", comment, ASSISTANT, answers, documents)


# The reviewer group each reviewer reviews for, kept on their review so other reviewers can say whose issues they read
GROUPS = {"reb-reviewer": "reb-reviewers", "reb-reviewer-2": "reb-reviewers", "digital-reviewer": "digital-reviewers"}


def open_review(admin, path, schema, reviewer, requirement):
    create(admin, f"{path}/review-{reviewer}", {
        "jcr:primaryType": "sub:Review", "reviewer": reviewer, "group": GROUPS[reviewer],
        "requirement": schema[requirement],
        "requirement@TypeHint": "Reference", "tags": ["in-progress"], "tags@TypeHint": "String[]"})


# The letter that went with the request for changes, as the reviewer would have sent it
def letter(issues):
    points = [f"{index + 1}. {issue['text']}" for index, issue in enumerate(issues)]
    return "\n".join(["Thank you for your submission. The research ethics review asks that you respond to the "
                      "points below, revise the submission where it applies, and send it back.", "", *points, "",
                      "Sincerely,", "", "Sam Okafor", "On behalf of the research ethics review"])


def send_back(admin, path, answers, documents, issues, url):
    for index, issue in enumerate(issues):
        add_comment(admin, f"{path}/review-reb-reviewer/issue{index}", issue, "reb-reviewer", answers, documents)
    reviewer = Instance(url, "reb-reviewer")
    reviewer.post(open_task(admin, path, "rebTask") + ".json",
                  {"outcome": "changes-requested", "outcomeNote": letter(issues)})


def add_activity(url, path, activity):
    people = {}

    def person(user):
        people.setdefault(user, Instance(url, user))
        return people[user]
    for user, notes in activity.get("notes", {}).items():
        for text in notes:
            person(user).post(f"{path}.reviewComment.json", {"kind": "note", "text": text})
    for user, text, replies in activity.get("discussion", []):
        person(user).post(f"{path}.reviewComment.json", {"kind": "discussion", "text": text})
        discussion = admin_view(url).get(f"{path}/discussion.1.json")
        latest = sorted((node for node in discussion.values() if isinstance(node, dict)
                         and node.get("jcr:primaryType") == "sub:Comment"), key=lambda node: node["jcr:created"])[-1]
        for reply_user, reply in replies:
            person(reply_user).post(f"{path}.reviewComment.json", {"replyTo": latest["@path"], "text": reply})


_ADMIN = {}


def admin_view(url):
    _ADMIN.setdefault(url, Instance(url, "admin"))
    return _ADMIN[url]


def stage(admin, path, study, setting, now):
    sent = now - datetime.timedelta(days=setting["age"], hours=3)
    window = 5 if setting.get("fastTrack") else 15
    fields = {"submittedAt": iso(sent), "submittedAt@TypeHint": "Date",
              "reviewDue": iso(sent + datetime.timedelta(days=window)), "reviewDue@TypeHint": "Date"}
    if setting.get("paused"):
        fields.update({"reviewPausedAt": iso(now - datetime.timedelta(days=setting["paused"])),
                       "reviewPausedAt@TypeHint": "Date"})
    if setting.get("fastTrack"):
        tags = admin.get(f"{path}.json").get("tags", [])
        fields["tags"] = sorted(set(tags) | {"fast-track"})
        fields["tags@TypeHint"] = "String[]"
    admin.post(f"{path}.import", form(fields))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8083")
    parser.add_argument("--content", default=str(pathlib.Path(__file__).resolve().parent.parent / "seed"))
    parser.add_argument("--only", nargs="*")
    args = parser.parse_args()
    content = pathlib.Path(args.content)
    admin = Instance(args.url, "admin")
    researcher = Instance(args.url, "researcher")
    now = datetime.datetime.now().astimezone()
    schema = schema_parts(admin)
    # Oldest first, so identifiers follow the order studies were sent in
    for nct, setting in sorted(STAGES.items(), key=lambda item: -item[1]["age"]):
        if args.only and nct not in args.only:
            continue
        study = json.loads((content / "content" / f"{nct}.json").read_text())
        path = file_study(researcher, admin, content, study)
        send(researcher, admin, path)
        answers, documents = submission_parts(admin, path, schema)
        add_extractions(admin, study, answers, documents)
        add_screening(admin, path, study, schema, answers, documents)
        open_review(admin, path, schema, "reb-reviewer", "reb")
        if nct in SENT_BACK:
            send_back(admin, path, answers, documents, SENT_BACK[nct], args.url)
        add_activity(args.url, path, ACTIVITY.get(nct, {}))
        # Last: sending a study back pauses the clock now, and this dates everything as the stage says
        stage(admin, path, study, setting, now)
        open_review(admin, path, schema, "digital-reviewer", "digitalReview")
        print(nct, path, admin.get(f"{path}.json").get("identifier"))


if __name__ == "__main__":
    sys.exit(main())
