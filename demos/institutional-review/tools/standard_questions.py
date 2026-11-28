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

"""The REB's standard questions, risk by risk: the wording the board agreed for what it asks most. PRETOTYPE
placeholders, written for the demo, not wording any board approved.

Each entry: its name, what it asks the study team, the policy it rests on, its type (required-change, question or
suggestion), the severity of a required change, and the words that tell an AI finding raised it.

    python3 demos/institutional-review/tools/standard_questions.py [--url http://localhost:8083] [--screenings]

writes them into the schema content under each REB risk, and with `--url` onto a running instance as well; with
`--screenings`, also links the AI findings already there to the entry each raised.
"""

import argparse
import json
import pathlib
import sys

import requests

SCHEMA_FILE = (pathlib.Path(__file__).resolve().parent.parent
               / "src/main/resources/SLING-INF/content/Schemas/retrospectiveStudy.json")
SCHEMA = "/Schemas/retrospectiveStudy/v1"
RESOURCE_TYPE = "sch/StandardQuestion"

STANDARDS = {
    "classification": [
        ("researchOrQi", "Please explain why this work is research rather than quality improvement, and say whether "
         "QIRC has looked at it.", "TCPS 2, Art. 2.5", "question", None,
         ["quality improvement", "qi ", "qirc"]),
        ("contact", "The protocol describes contact with participants or new data collection. Please revise the "
         "study classification, or explain why no one is contacted and nothing new is collected.",
         "TCPS 2, Art. 5.6", "required-change", "major", ["contact", "new collection", "x-ray", "radiation"]),
        ("originalPurpose", "Please state why the information was originally collected, and by whom.",
         "TCPS 2, Ch. 5 §D", "question", None, ["original purpose", "originally collected", "clinical care"]),
    ],
    "design": [
        ("components", "The design goes beyond a chart review. Please describe each component and how it is carried "
         "out.", "TCPS 2, Art. 2.7", "question", None, ["mixed", "survey", "interview", "component"]),
    ],
    "identifiability": [
        ("coded", "Please correct the identifiability answers: data the team, or someone the team can reach, can link "
         "back to a person is coded, not anonymous. Say who holds the key and where it is kept.",
         "TCPS 2, Ch. 5 §A", "required-change", "major", ["anonymi", "key", "coded", "link every", "link back"]),
        ("fields", "Please explain why each identifying field is needed (such as names, full dates, MRN or postal "
         "code), or replace it with a less identifying one (such as age, year or region).",
         "TCPS 2, Art. 5.5A(a)", "required-change", "medium",
         ["calendar date", "dates", "free text", "free-text", "dictated", "postal", "field", "name or id",
          "staff surgeon"]),
    ],
    "waiver": [
        ("impracticable", "Please explain why seeking consent is impracticable for this cohort, for example its size, "
         "how long ago care took place, or how many people can no longer be reached. Cost or inconvenience alone does "
         "not meet this condition.", "TCPS 2, Art. 5.5A(c)", "required-change", "medium",
         ["impracticab", "cost", "funding", "coordinator time", "reach", "cohort size"]),
        ("whyIdentifiable", "Please explain why identifiable information is essential to the research.",
         "TCPS 2, Art. 5.5A(a)", "required-change", "medium", ["essential", "why even coded", "not identifiable"]),
        ("secondaryUse", "Article 3.7A does not apply to the secondary use of information. Please request a waiver "
         "under Article 5.5A and address each of its conditions.", "TCPS 2, Art. 5.5A", "required-change", "major",
         ["3.7a", "not applicable", "prior consent", "own consent"]),
    ],
    "authority": [
        ("custodianApproval", "Please attach the data custodian's written approval for this use of the data.",
         "TCPS 2, Art. 5.5A(f)", "required-change", "medium", ["custodian", "written approval"]),
        ("agreement", "Please give the status of the data sharing agreement. The data should not be transferred until "
         "it is signed.", "TCPS 2, Art. 5.5A(f); PHIPA", "required-change", "medium",
         ["agreement", "drafted", "signed"]),
        ("termsOfUse", "The data was collected outside PHIPA. Please attach the terms people accepted, and point to "
         "the clause that allows its use for research.", "TCPS 2, Art. 5.5A(f); PIPEDA", "question", None,
         ["terms of use", "privacy policy", "pipeda", "outside phipa"]),
    ],
    "linkage": [
        ("plan", "Please describe the linkage: which datasets, who performs it, on which identifiers, and when the "
         "linking file is destroyed.", "TCPS 2, Art. 5.7", "required-change", "medium",
         ["linkage", "linking", "merge", "linked"]),
        ("reidentification", "Please assess how likely re-identification is once the datasets are linked, and the "
         "safeguards proportionate to it.", "TCPS 2, Art. 5.7", "question", None, ["re-identif"]),
    ],
    "indigenous": [
        ("engagement", "The data may be identifiable as coming from a First Nations, Inuit or Métis community. Please "
         "describe the engagement with the community, or explain why it is not needed.", "TCPS 2, Art. 9.22",
         "required-change", "major", ["indigenous", "first nations", "inuit", "métis", "community"]),
    ],
    "sensitive": [
        ("categories", "Please review the sensitive categories against the data the study uses, tick those that "
         "apply, and reconsider the harm a breach could do.", "TCPS 2, Art. 5.5A; Ch. 4", "required-change", "medium",
         ["sensitive categor", "genetic", "reproductive", "mental health", "tick"]),
        ("safeguards", "Please describe any additional safeguards for the vulnerable groups in the cohort.",
         "TCPS 2, Ch. 4", "question", None, ["vulnerable", "stigma"]),
    ],
    "transfer": [
        ("destinations", "Please name every party, service or tool that receives the data (including transcription, "
         "statistical consultants and AI tools), where each is hosted, and the safeguard for each.",
         "TCPS 2, Art. 5.3", "required-change", "medium",
         ["vendor", "external", "us ", "cloud", "hosted", "portal", "tool", "leaves"]),
        ("assessments", "The data leaves UHN systems. Please say whether a privacy impact assessment and a threat risk "
         "assessment have been requested.", "TCPS 2, Art. 5.3; UHN Policy 1.40.028", "question", None,
         ["pia", "tra", "impact assessment"]),
    ],
    "safeguards": [
        ("retention", "Please state how long the data is kept, where, and how it is destroyed at the end.",
         "TCPS 2, Art. 5.3; UHN Policy 1.30.007", "required-change", "minor",
         ["retention", "destroyed", "kept", "how long", "archived"]),
        ("access", "Please say who has access to the study data, and how that access is controlled.",
         "TCPS 2, Art. 5.3", "question", None, ["access", "drive", "transfer method", "receives the file"]),
    ],
    "rigour": [
        ("criteria", "Please explain how the inclusion and exclusion criteria suit the research question.",
         "TCPS 2, Art. 2.7", "question", None, ["criteria", "inclusion", "exclusion", "window", "truncat", "cohort"]),
        ("analysis", "Consider describing how missing or incomplete data are handled in the analysis.",
         "TCPS 2, Art. 2.7", "suggestion", None, ["missing", "analysis", "formula", "sign", "calibration"]),
    ],
    "equity": [
        ("exclusions", "Please explain why any groups are left out (for example by language, age or sex), and what "
         "this means for who the results apply to.", "TCPS 2, Art. 4.1", "question", None,
         ["language", "excluded", "left out", "income", "ethnicity"]),
        ("makeUp", "Consider reporting the cohort's make-up, so readers can judge who the results apply to.",
         "TCPS 2, Art. 4.7", "suggestion", None, ["make-up", "self-selected", "differ from the wider"]),
    ],
    "reporting": [
        ("smallCells", "Please state the smallest cell size reported, and how smaller cells are suppressed or merged.",
         "TCPS 2, Art. 5.3", "required-change", "minor", ["cell", "suppress", "small", "rare"]),
    ],
    "incidental": [
        ("plan", "Please describe what happens if a clinically actionable finding comes up while records are reviewed.",
         "TCPS 2, Art. 3.4", "question", None, ["incidental", "actionable"]),
    ],
    "terminology": [
        ("terms", "Please use terms as TCPS 2 defines them (such as anonymous, de-identified and coded), and describe "
         "the population and analyses by what they are.", "TCPS 2, Ch. 5 §A", "suggestion", None,
         ["called", "calls the study", "itt", "efficacy", "term", "overstates"]),
    ],
    "conflict": [
        ("declare", "Please declare the team's relationship with the sponsor or vendor (funding, and any consulting, "
         "advisory or equity ties) and describe how the conflict is managed.", "TCPS 2, Art. 7.4", "required-change",
         "major", ["conflict", "sponsor", "ceo", "funds", "equity"]),
        ("control", "Please state who controls the analysis, the interpretation and the decision to publish.",
         "TCPS 2, Art. 7.4", "question", None, ["publish", "controls the analysis"]),
    ],
    "dateRange": [
        ("endDate", "The collection period reaches into the recent past. Please explain why consent cannot be sought "
         "from people seen recently, or move the end date back.", "TCPS 2, Art. 5.5A(c)", "question", None,
         ["end date", "recent", "still being seen", "data ends", "before approval"]),
    ],
    "ai": [
        ("training", "Please confirm whether study data or annotations are used to train or refine an AI model, by the "
         "team or a vendor.", "UHN AI Hub guidance", "question", None, ["train", "refine"]),
        ("intake", "Please confirm the AI tool has been through the AI Hub intake.", "UHN AI Hub guidance", "question",
         None, ["ai hub", "intake"]),
    ],
}


def entry_node(text, citation, nature, severity):
    node = {"jcr:primaryType": "nt:unstructured", "sling:resourceType": RESOURCE_TYPE, "text": text,
            "citation": citation, "nature": nature}
    if severity:
        node["severity"] = severity
    return node


def match(topic, text):
    """The standard question of the REB risk an AI finding raised, by name, from the words it uses; None when it raised
    none of them, or the risk has none."""
    said = text.lower()
    best, hits = None, 0
    for name, *_, words in STANDARDS.get(topic, []):
        found = sum(1 for word in words if word in said)
        if found > hits:
            best, hits = name, found
    return best


def citation_of(topic, name):
    return next(entry[2] for entry in STANDARDS[topic] if entry[0] == name)


def write_schema_file():
    schema = json.loads(SCHEMA_FILE.read_text())
    reb = schema["v1"]["reb"]
    for topic, entries in STANDARDS.items():
        node = reb[topic]
        for key in [key for key, value in node.items() if isinstance(value, dict)
                    and value.get("sling:resourceType") == RESOURCE_TYPE]:
            del node[key]
        for name, text, citation, nature, severity, _ in entries:
            node[name] = entry_node(text, citation, nature, severity)
    SCHEMA_FILE.write_text(json.dumps(schema, indent=2, ensure_ascii=False) + "\n")


def put(session, url, path, name, value):
    response = session.put(f"{url}/bin/cpm/nodes/property.put.json{path}",
                           json={"name": name, "value": value, "type": "String", "multi": False})
    response.raise_for_status()


def write_live(url):
    """The entries on a running instance, through Composum, as schema content takes no plain POST."""
    session = requests.Session()
    session.auth = ("admin", "admin")
    for topic, entries in STANDARDS.items():
        for name, text, citation, nature, severity, _ in entries:
            path = f"{SCHEMA}/reb/{topic}/{name}"
            if session.get(f"{url}{path}.json").status_code == 404:
                session.post(f"{url}/bin/cpm/nodes/node.create.json{SCHEMA}/reb/{topic}",
                             data={"name": name, "type": "nt:unstructured"}).raise_for_status()
            for key, value in entry_node(text, citation, nature, severity).items():
                if key != "jcr:primaryType":
                    put(session, url, path, key, value)


def link_screenings(url):
    """The AI findings already on a running instance linked to the standard question each raised."""
    session = requests.Session()
    session.auth = ("admin", "admin")

    def submissions(node, path, depth):
        for name, child in node.items():
            if isinstance(child, dict):
                if depth == 3:
                    yield f"{path}/{name}"
                else:
                    yield from submissions(child, f"{path}/{name}", depth + 1)
    paths = [f"{one}/screening" for one in submissions(session.get(f"{url}/Submissions.4.json").json(), "/Submissions", 0)]
    linked = 0
    for screening in paths:
        response = session.get(f"{url}{screening}.2.json")
        if not response.ok:
            continue
        tree = response.json()
        for assessment, node in tree.items():
            if not isinstance(node, dict) or not assessment.startswith("reb-"):
                continue
            topic = assessment[len("reb-"):]
            for name, comment in node.items():
                if isinstance(comment, dict) and comment.get("jcr:primaryType") == "sub:Comment":
                    standard = match(topic, comment.get("text", ""))
                    if standard:
                        path = f"{screening}/{assessment}/{name}"
                        put(session, url, path, "standard", f"{SCHEMA}/reb/{topic}/{standard}")
                        put(session, url, path, "citation", citation_of(topic, standard))
                        linked += 1
    print(f"{linked} AI findings linked")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url")
    parser.add_argument("--screenings", action="store_true")
    args = parser.parse_args()
    write_schema_file()
    print(f"{sum(len(entries) for entries in STANDARDS.values())} standard questions written to {SCHEMA_FILE.name}")
    if args.url:
        write_live(args.url)
        print("and onto", args.url)
        if args.screenings:
            link_screenings(args.url)
    return 0


if __name__ == "__main__":
    sys.exit(main())
