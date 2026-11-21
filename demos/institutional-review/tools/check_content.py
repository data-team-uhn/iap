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

"""Checks the seed content against the schema and the protocols. PRETOTYPE.

Every answer must fit its question and be asked given the other answers, every required question that is asked
must be answered, and every quote must occur in its protocol, as PDF.js reads it.

    python3 demos/institutional-review/tools/check_content.py
"""
import importlib.util, json, pathlib, re, subprocess, tempfile
HERE = pathlib.Path(__file__).resolve().parent
SEED = HERE.parent / "seed"
NODE = HERE.parents[2] / "aggregated-frontend/src/main/frontend/node/node"
PDFJS = HERE.parents[2] / "aggregated-frontend/src/main/frontend/node_modules/pdfjs-dist/legacy/build/pdf.mjs"
spec = importlib.util.spec_from_file_location("b", HERE / "build_schema.py")
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
qs = {}
def walk(prefix, items, when):
    for it in items:
        if "section" in it:
            walk(f"{prefix}/{it['section']}", it["items"], when + ([it["when"]] if it.get("when") else []))
        else:
            qs[f"{prefix}/{it['name']}"] = (it, when + ([it["when"]] if it.get("when") else []))
for name, _, _, items in m.FORMS: walk(name, items, [])
topics = {(a[0], t[0]) for a in m.APPROVALS for t in a[5]}
docs = {d[0] for d in m.DOCUMENTS} | {"sap"}
def holds(answers, w):
    path, comp, val = w; a = answers.get(path)
    vals = a if isinstance(a, list) else ([] if a is None else [a])
    if comp == "equals": return vals == [val]
    if comp == "not equals": return vals != [val]
    if comp == "excludes": return val not in vals
    raise ValueError(comp)
norm = lambda s: re.sub(r"\s+", " ", s).strip().lower()
total = 0
scratch = pathlib.Path(tempfile.mkdtemp())
for f in sorted((SEED / "content").glob("NCT*.json")):
    d = json.load(open(f)); problems = []
    texts = {}
    pdfs = sorted((SEED / "protocols" / d["nct"]).glob("*.pdf"))
    out = scratch / d["nct"]; out.mkdir()
    subprocess.run([str(NODE), str(HERE / "pdftext.mjs"), PDFJS.as_uri(), str(out), *map(str, pdfs)], check=True, capture_output=True)
    for t in out.glob("*.txt"):
        pages = re.split(r"\n=== page (\d+)\n", "\n" + t.read_text())
        texts[t.stem] = {int(pages[i]): norm(pages[i + 1]) for i in range(1, len(pages), 2)}
    def find(quote, page, document=None):
        return any(norm(quote) in v.get(page, "") for v in texts.values())
    A = d["answers"]
    for k, v in A.items():
        if k not in qs: problems.append(f"unknown question {k}"); continue
        it, when = qs[k]
        opts = {o[0] for o in it["options"]}
        vals = v if isinstance(v, list) else [v]
        if it["kind"] == "many" and not isinstance(v, list): problems.append(f"{k} should be a list")
        if opts and any(x not in opts for x in vals): problems.append(f"{k} bad option {v}")
        if it["kind"] == "yesno" and not isinstance(v, bool): problems.append(f"{k} not boolean")
        if not all(holds(A, w) for w in when): problems.append(f"{k} answered but not asked")
    for k, (it, when) in qs.items():
        if k not in A and it.get("required", True) and all(holds(A, w) for w in when): problems.append(f"missing {k}")
    for e in d.get("extractions", []):
        if e["question"] not in qs: problems.append(f"extraction for unknown {e['question']}")
        if not find(e["quote"], e["page"], e.get("document")): problems.append(f"quote not found p{e['page']}: {e['quote'][:50]}")
    for a in d["screening"]["assessments"]:
        if (a["approval"], a["topic"]) not in topics: problems.append(f"unknown topic {a['approval']}/{a['topic']}")
        for c in a["comments"]:
            if c["severity"] not in ("critical", "major", "medium", "minor", "suggestion"): problems.append("bad severity")
            for s in c.get("subjects", []):
                if s not in qs and s not in docs: problems.append(f"bad subject {s}")
            if c.get("quote") and not find(c["quote"], c.get("page", 0), c.get("document")): problems.append(f"comment quote not found: {c['quote'][:50]}")
    n = sum(len(a["comments"]) for a in d["screening"]["assessments"])
    print(f"{d['nct']} {d['title'][:50]!r}: {len(A)} answers, {len(d.get('extractions', []))} extractions, {n} AI comments, {len(d.get('variableList') or [])} variables, dsa={'yes' if d.get('dataSharingAgreement') else 'no'}")
    for p in problems: print("   !", p)
    total += len(problems)
print("problems:", total)

