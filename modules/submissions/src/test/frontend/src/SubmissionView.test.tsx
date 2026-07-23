/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";

import SubmissionView from "@iap/submissions/SubmissionView";

// A submission as returned by the `deep` serialization: children nested, references expanded
const DEEP_SUBMISSION = {
  "@path": "/Submissions/demo-1",
  "@name": "demo-1",
  "sling:resourceType": "sub/Submission",
  "title": "Test my drug",
  "status": "in-review",
  "jcr:created": "2026-07-01T10:00:00.000-04:00",
  "jcr:createdBy": "admin",
  "jcr:lastModified": "2026-07-02T10:00:00.000-04:00",
  "schemaVersion": {
    "@path": "/Schemas/ClinicalTrial/1.0",
    "@name": "1.0",
    "sling:resourceType": "sch/SchemaVersion",
    "version": "1.0",
    "BasicInformation": {
      "@path": "/Schemas/ClinicalTrial/1.0/BasicInformation",
      "sling:resourceType": "sch/FormRequirement",
      "label": "Basic information",
      "description": "General information about the study",
      "StudyTitle": {
        "@path": "/Schemas/ClinicalTrial/1.0/BasicInformation/StudyTitle",
        "sling:resourceType": "sch/Question",
        "text": "What is the full title of the study?",
      },
      "Contact": {
        "@path": "/Schemas/ClinicalTrial/1.0/BasicInformation/Contact",
        "sling:resourceType": "sch/Section",
        "title": "Contact details",
        "Email": {
          "@path": "/Schemas/ClinicalTrial/1.0/BasicInformation/Contact/Email",
          "sling:resourceType": "sch/Question",
          "text": "What is the contact email?",
        },
      },
    },
    "Protocol": {
      "@path": "/Schemas/ClinicalTrial/1.0/Protocol",
      "sling:resourceType": "sch/DocumentRequirement",
      "label": "Study protocol",
    },
  },
  "a1": {
    "@path": "/Submissions/demo-1/a1",
    "sling:resourceType": "sub/Answer",
    "question": {
      "@path": "/Schemas/ClinicalTrial/1.0/BasicInformation/StudyTitle",
      "sling:resourceType": "sch/Question",
      "text": "What is the full title of the study?",
    },
    "value": "A wonder drug against everything",
  },
  "r1": {
    "@path": "/Submissions/demo-1/r1",
    "sling:resourceType": "sub/Review",
    "reviewer": "jdoe",
    "status": "changes-requested",
    "c1": {
      "@path": "/Submissions/demo-1/r1/c1",
      "sling:resourceType": "sub/ReviewComment",
      "author": "jdoe",
      "text": "Please clarify the dosage",
      "resolved": false,
      "reply1": {
        "@path": "/Submissions/demo-1/r1/c1/reply1",
        "sling:resourceType": "sub/Reply",
        "author": "admin",
        "text": "Clarified in the summary",
      },
    },
  },
};

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SubmissionView />
    </MemoryRouter>
  );
}

describe("SubmissionView", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("displays the submission's answers, per the schema's structure, and its reviews", async () => {
    const fetchMock = vi.fn<(url: RequestInfo | URL) => Promise<Response>>(() => Promise.resolve(
      { ok: true, json: () => Promise.resolve(DEEP_SUBMISSION) } as unknown as Response));
    vi.stubGlobal("fetch", fetchMock);

    renderAt("/Submissions/demo-1");

    // Header: title, status, schema, creator
    expect(await screen.findByText("Test my drug")).toBeInTheDocument();
    expect(screen.getByText("in-review")).toBeInTheDocument();
    expect(screen.getByText(/ClinicalTrial 1.0/)).toBeInTheDocument();
    expect(screen.getByText(/by admin/)).toBeInTheDocument();

    // The submission itself was fetched with the deep serialization
    expect(String(fetchMock.mock.calls[0][0])).toBe("/Submissions/demo-1.deep.json");

    // The form requirement, its section, and its questions, with and without answers
    expect(screen.getByText("Basic information")).toBeInTheDocument();
    expect(screen.getByText("Contact details")).toBeInTheDocument();
    expect(screen.getByText("What is the full title of the study?")).toBeInTheDocument();
    expect(screen.getByText("A wonder drug against everything")).toBeInTheDocument();
    expect(screen.getByText("What is the contact email?")).toBeInTheDocument();
    expect(screen.getByText("Not answered yet")).toBeInTheDocument();

    // No documents attached, but the schema says one is expected
    expect(screen.getByText(/No documents attached yet/)).toBeInTheDocument();
    expect(screen.getByText(/expected: Study protocol/)).toBeInTheDocument();

    // The review with its threaded comment ("jdoe" appears as reviewer and as comment author)
    expect(screen.getAllByText("jdoe").length).toBeGreaterThan(0);
    expect(screen.getByText("changes-requested")).toBeInTheDocument();
    expect(screen.getByText(/Please clarify the dosage/)).toBeInTheDocument();
    expect(screen.getByText(/Clarified in the summary/)).toBeInTheDocument();
  });

  it("tolerates a .html suffix in the page URL", async () => {
    const fetchMock = vi.fn<(url: RequestInfo | URL) => Promise<Response>>(() => Promise.resolve(
      { ok: true, json: () => Promise.resolve(DEEP_SUBMISSION) } as unknown as Response));
    vi.stubGlobal("fetch", fetchMock);

    renderAt("/Submissions/demo-1.html");

    expect(await screen.findByText("Test my drug")).toBeInTheDocument();
    expect(String(fetchMock.mock.calls[0][0])).toBe("/Submissions/demo-1.deep.json");
  });

  it("reports inaccessible submissions", async () => {
    vi.stubGlobal("fetch", vi.fn<(url: RequestInfo | URL) => Promise<Response>>(
      () => Promise.resolve({ ok: false, status: 404 } as unknown as Response)));

    renderAt("/Submissions/nonexistent");

    expect(await screen.findByText("This submission cannot be loaded (404)")).toBeInTheDocument();
  });
});
