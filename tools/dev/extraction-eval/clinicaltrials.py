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

"""Builds a public evaluation set for answer extraction from ClinicalTrials.gov.

Every study picked has its full protocol as a public PDF, and the registry's own fields give the
expected answers for questions/clinical-trial-protocol.json.

    clinicaltrials.py select DATASET [--count N] [--seed S]   pick studies, write manifest.json
    clinicaltrials.py fetch DATASET --docling PYTHON          download and convert the protocols
    clinicaltrials.py expected DATASET                        write expected.json from the registry

The registry describes the study as it stands now, and a protocol PDF may be an older amendment, so a
disagreement is something to look at, not always a wrong answer.
"""

import argparse
import http.client
import json
import os
import random
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

STUDIES_API_URL = "https://clinicaltrials.gov/api/v2/studies"
LARGE_DOCS_BASE_URL = "https://cdn.clinicaltrials.gov/large-docs"
PARSER_DIR = Path(__file__).resolve().parents[3] / "modules/documents/processing/src/main/python"
CONVERTER_SCRIPT = Path(__file__).resolve().parent / "convert_protocols.py"

# A protocol download that stalls this long is given up, and tried again on the next run
DOWNLOAD_TIMEOUT_SECONDS = 120

# Big enough to pick from, small enough to fetch in a few requests
CANDIDATE_PAGES = 3
PAGE_SIZE = 1000

# A protocol past this is slow to convert on a CPU, and says nothing a shorter one does not
MAX_PROTOCOL_BYTES = 6_000_000

PHASES = {
    ("EARLY_PHASE1",): "early1",
    ("PHASE1",): "1",
    ("PHASE1", "PHASE2"): "1/2",
    ("PHASE2",): "2",
    ("PHASE2", "PHASE3"): "2/3",
    ("PHASE3",): "3",
    ("PHASE4",): "4",
    ("NA",): "none",
}
ALLOCATIONS = {"RANDOMIZED": "randomized", "NON_RANDOMIZED": "nonRandomized", "NA": "singleArm"}
MASKINGS = {"NONE": "open", "SINGLE": "single", "DOUBLE": "double", "TRIPLE": "double", "QUADRUPLE": "double"}
SEXES = {"ALL": "all", "FEMALE": "female", "MALE": "male"}
INTERVENTIONS = {
    "DRUG": "drug", "BIOLOGICAL": "biological", "DEVICE": "device", "PROCEDURE": "procedure",
    "RADIATION": "radiation", "BEHAVIORAL": "behavioral", "DIETARY_SUPPLEMENT": "dietarySupplement",
    "DIAGNOSTIC_TEST": "diagnosticTest", "GENETIC": "genetic", "COMBINATION_PRODUCT": "drug",
    "OTHER": "other",
}
AGE_UNITS = {"year": 1, "years": 1, "month": 1 / 12, "months": 1 / 12, "week": 1 / 52, "weeks": 1 / 52}


def get_json(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def fetch_candidates():
    """Interventional studies with a protocol document, as the registry describes them."""
    studies, token = [], None
    for _ in range(CANDIDATE_PAGES):
        params = {
            "query.term": "AREA[LargeDocHasProtocol]true AND AREA[StudyType]INTERVENTIONAL",
            "pageSize": PAGE_SIZE,
            # Only what the selection reads: the results a study posted are often most of its record
            "fields": "protocolSection,documentSection",
        }
        if token:
            params["pageToken"] = token
        page = get_json(STUDIES_API_URL + "?" + urllib.parse.urlencode(params))
        studies.extend(page.get("studies", []))
        token = page.get("nextPageToken")
        if not token:
            break
    return studies


def find_protocol_document(study):
    """The study's protocol PDF, the smallest one when it has several, or None."""
    docs = study.get("documentSection", {}).get("largeDocumentModule", {}).get("largeDocs", [])
    protocols = [d for d in docs if d.get("hasProtocol") and d.get("filename", "").lower().endswith(".pdf")
                 and 0 < d.get("size", 0) <= MAX_PROTOCOL_BYTES]
    return min(protocols, key=lambda d: d["size"]) if protocols else None


def get_stratum(study):
    """What the selection spreads over: the phase and the first kind of intervention."""
    protocol = study["protocolSection"]
    phase = PHASES.get(tuple(protocol.get("designModule", {}).get("phases", [])), "?")
    kinds = protocol.get("armsInterventionsModule", {}).get("interventions", [])
    return phase, kinds[0].get("type", "?") if kinds else "?"


def select(dataset, count, seed):
    candidates = [s for s in fetch_candidates() if find_protocol_document(s)]
    groups = {}
    for study in candidates:
        groups.setdefault(get_stratum(study), []).append(study)
    rng = random.Random(seed)
    for members in groups.values():
        rng.shuffle(members)
    picked, keys = [], sorted(groups)
    # Round robin over the strata, so a rare kind of study is not crowded out by the common ones
    while len(picked) < count and any(groups.values()):
        for key in keys:
            if groups[key] and len(picked) < count:
                picked.append(groups[key].pop())
    manifest = []
    for study in picked:
        nct = study["protocolSection"]["identificationModule"]["nctId"]
        document = find_protocol_document(study)
        manifest.append({"id": nct, "file": document["filename"], "bytes": document["size"],
                         "url": f"{LARGE_DOCS_BASE_URL}/{nct[-2:]}/{nct}/{document['filename']}",
                         "stratum": list(get_stratum(study))})
    dataset.mkdir(parents=True, exist_ok=True)
    (dataset / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (dataset / "registry.json").write_text(json.dumps(
        {s["protocolSection"]["identificationModule"]["nctId"]: s["protocolSection"] for s in picked}, indent=2))
    print(f"Picked {len(manifest)} of {len(candidates)} candidates over {len(groups)} strata")


def download(entry, sources):
    """The study's protocol PDF, downloaded unless an earlier run has it, or None when the download fails."""
    pdf = sources / f"{entry['id']}.pdf"
    if pdf.exists():
        return pdf
    partial = pdf.with_suffix(".part")
    try:
        with urllib.request.urlopen(entry["url"], timeout=DOWNLOAD_TIMEOUT_SECONDS) as response, \
                partial.open("wb") as out:
            shutil.copyfileobj(response, out)
    except (OSError, http.client.HTTPException) as error:
        partial.unlink(missing_ok=True)
        print(f"{entry['id']}: download failed: {error}", flush=True)
        return None
    # A connection closed cleanly mid-body ends the copy without an error, so the size is what tells
    if partial.stat().st_size != entry["bytes"]:
        print(f"{entry['id']}: download cut short: {partial.stat().st_size} of {entry['bytes']} bytes",
              flush=True)
        partial.unlink()
        return None
    # Named as a PDF only once complete, so a broken download is fetched again on the next run
    partial.replace(pdf)
    return pdf


def fetch(dataset, docling):
    manifest = json.loads((dataset / "manifest.json").read_text())
    sources, documents = dataset / "sources", dataset / "documents"
    sources.mkdir(exist_ok=True)
    documents.mkdir(exist_ok=True)
    pending = [entry for entry in manifest if not (documents / f"{entry['id']}.md").exists()]
    pdfs = [pdf for pdf in (download(entry, sources) for entry in pending) if pdf]
    if not pdfs:
        return
    started = time.monotonic()
    # One process for the whole set, so the models load once. The parser only writes inside the shared
    # folder it is given, and reads its settings relative to its own folder.
    conversion = subprocess.Popen([docling, str(CONVERTER_SCRIPT), *map(str, pdfs)], stdout=subprocess.PIPE,
                                  stdin=subprocess.DEVNULL, text=True, cwd=PARSER_DIR,
                                  env={**os.environ, "IAP_SHARED_DOCS": str(sources)})
    for line in conversion.stdout:
        # Docling's own output goes to the same stream; only the result lines are JSON
        if not line.startswith('{"pdf"'):
            continue
        result = json.loads(line)
        nct = Path(result["pdf"]).stem
        if "error" in result:
            print(f"{nct}: conversion failed: {result['error'][-300:]}", flush=True)
            continue
        markdown = documents / f"{nct}.md"
        Path(result["markdown"]).replace(markdown)
        print(f"{nct}: {markdown.stat().st_size} characters, {time.monotonic() - started:.0f} s so far", flush=True)
    if conversion.wait() != 0:
        print(f"The converter stopped with exit code {conversion.returncode}", flush=True)


def find_python(name):
    """The interpreter as an absolute path, so it still runs once the converter's folder is the working one."""
    path = Path(name)
    if path.exists():
        return str(path.resolve())
    return shutil.which(name) or name


def parse_age_in_years(age):
    """A registry age such as '18 Years' or '6 Months', in whole years, or None."""
    match = re.fullmatch(r"(\d+)\s+(\w+)", age or "")
    if not match or match.group(2).lower() not in AGE_UNITS:
        return None
    return int(int(match.group(1)) * AGE_UNITS[match.group(2).lower()])


def build_expected_answers(protocol):
    identification = protocol["identificationModule"]
    design = protocol.get("designModule", {})
    eligibility = protocol.get("eligibilityModule", {})
    officials = protocol.get("contactsLocationsModule", {}).get("overallOfficials", [])
    enrollment = design.get("enrollmentInfo", {})
    kinds = sorted({INTERVENTIONS.get(i.get("type"), "other")
                    for i in protocol.get("armsInterventionsModule", {}).get("interventions", [])})
    outcomes = protocol.get("outcomesModule", {}).get("primaryOutcomes", [])
    answers = {
        "study.title": {"value": identification.get("officialTitle") or identification.get("briefTitle"),
                        "alternatives": [identification.get("briefTitle")]},
        "study.sponsor": protocol.get("sponsorCollaboratorsModule", {}).get("leadSponsor", {}).get("name"),
        "study.leadInvestigator": officials[0].get("name") if officials else None,
        "study.phase": PHASES.get(tuple(design.get("phases", []))),
        "design.allocation": ALLOCATIONS.get(design.get("designInfo", {}).get("allocation")),
        "design.masking": MASKINGS.get(design.get("designInfo", {}).get("maskingInfo", {}).get("masking")),
        # An actual enrolment is what happened, the protocol says what was planned
        "participants.sampleSize": None if enrollment.get("count") is None else (
            enrollment["count"] if enrollment.get("type") == "ESTIMATED"
            else {"value": enrollment["count"], "tolerance": 0.25}),
        "participants.minimumAge": parse_age_in_years(eligibility.get("minimumAge")),
        "participants.sex": SEXES.get(eligibility.get("sex")),
        "participants.healthyVolunteers": eligibility.get("healthyVolunteers"),
        "intervention.kinds": kinds or None,
        "intervention.primaryOutcome": outcomes[0].get("measure") if outcomes else None,
    }
    return {key: value for key, value in answers.items() if value is not None}


def write_expected_answers(dataset):
    registry = json.loads((dataset / "registry.json").read_text())
    answers = {nct: build_expected_answers(protocol) for nct, protocol in registry.items()}
    (dataset / "expected.json").write_text(json.dumps(answers, indent=2) + "\n")
    print(f"Expected answers for {len(answers)} studies")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    select_parser = commands.add_parser("select")
    select_parser.add_argument("dataset", type=Path)
    select_parser.add_argument("--count", type=int, default=60)
    select_parser.add_argument("--seed", type=int, default=2026)
    fetch_parser = commands.add_parser("fetch")
    fetch_parser.add_argument("dataset", type=Path)
    fetch_parser.add_argument("--docling", required=True, help="the Python of an environment with Docling installed")
    expected_parser = commands.add_parser("expected")
    expected_parser.add_argument("dataset", type=Path)
    args = parser.parse_args()
    # Absolute, since the converter runs in another folder
    dataset = args.dataset.resolve()
    if args.command == "select":
        select(dataset, args.count, args.seed)
    elif args.command == "fetch":
        fetch(dataset, find_python(args.docling))
    else:
        write_expected_answers(dataset)


if __name__ == "__main__":
    sys.exit(main())
