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

import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";

import SubmissionView from "@iap/submissions/SubmissionView";
import { clearTagDefinitionsCache } from "@iap/tags/tagDefinitions";
import { jsonResponse, tagAwareFetch } from "@iap/tags/tagDefinitions.fixture";

// A submission as returned by the `deep` serialization: children nested, references expanded
const DEEP_SUBMISSION = {
  "@path": "/Submissions/demo-1",
  "@name": "demo-1",
  "sling:resourceType": "sub/Submission",
  "title": "Test my drug",
  "tags": ["in-review"],
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
      "Keywords": {
        "@path": "/Schemas/ClinicalTrial/1.0/BasicInformation/Keywords",
        "sling:resourceType": "sch/Question",
        "text": "Which keywords describe the study?",
      },
      "Blinded": {
        "@path": "/Schemas/ClinicalTrial/1.0/BasicInformation/Blinded",
        "sling:resourceType": "sch/Question",
        "text": "Is the study blinded?",
      },
      "Duration": {
        "@path": "/Schemas/ClinicalTrial/1.0/BasicInformation/Duration",
        "sling:resourceType": "sch/Question",
        // No text: the question falls back to its node name
        "@name": "Duration",
      },
      "Consent": {
        "@path": "/Schemas/ClinicalTrial/1.0/BasicInformation/Consent",
        "sling:resourceType": "sch/Question",
        "text": "Was consent obtained?",
      },
      "Guidance": {
        "@path": "/Schemas/ClinicalTrial/1.0/BasicInformation/Guidance",
        // Not a question or section: skipped by the renderer
        "sling:resourceType": "sch/InformationBlock",
        "text": "Fill this form carefully",
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
        "Address": {
          "@path": "/Schemas/ClinicalTrial/1.0/BasicInformation/Contact/Address",
          // A nested section, with its own description, rendered at a deeper heading level
          "sling:resourceType": "sch/Section",
          "title": "Mailing address",
          "description": "Where to send paper mail",
        },
        "Fax": {
          "@path": "/Schemas/ClinicalTrial/1.0/BasicInformation/Contact/Fax",
          // No title: the section falls back to its node name
          "sling:resourceType": "sch/Section",
          "@name": "Fax",
        },
      },
    },
    "Protocol": {
      "@path": "/Schemas/ClinicalTrial/1.0/Protocol",
      "sling:resourceType": "sch/DocumentRequirement",
      "label": "Study protocol",
    },
    "ExtraForm": {
      "@path": "/Schemas/ClinicalTrial/1.0/ExtraForm",
      // No label or description: the form's section falls back to the node name, no subtitle
      "sling:resourceType": "sch/FormRequirement",
      "@name": "ExtraForm",
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
  "a2": {
    "@path": "/Submissions/demo-1/a2",
    "sling:resourceType": "sub/Answer",
    "question": { "@path": "/Schemas/ClinicalTrial/1.0/BasicInformation/Keywords" },
    // A multi-valued answer, mixing in a non-string entry
    "value": ["pharmacology", true],
  },
  "a3": {
    "@path": "/Submissions/demo-1/a3",
    "sling:resourceType": "sub/Answer",
    "question": { "@path": "/Schemas/ClinicalTrial/1.0/BasicInformation/Blinded" },
    "value": false,
  },
  "a4": {
    "@path": "/Submissions/demo-1/a4",
    "sling:resourceType": "sub/Answer",
    "question": { "@path": "/Schemas/ClinicalTrial/1.0/BasicInformation/Duration" },
    "value": 36,
  },
  "a5": {
    "@path": "/Submissions/demo-1/a5",
    "sling:resourceType": "sub/Answer",
    "question": { "@path": "/Schemas/ClinicalTrial/1.0/BasicInformation/Consent" },
    // A nested node has no meaningful text form, so this reads as unanswered
    "value": { "jcr:primaryType": "nt:unstructured" },
  },
  "r1": {
    "@path": "/Submissions/demo-1/r1",
    "sling:resourceType": "sub/Review",
    "reviewer": "jdoe",
    "tags": ["changes-requested"],
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
  "r2": {
    "@path": "/Submissions/demo-1/r2",
    "sling:resourceType": "sub/Review",
    "reviewer": "asmith",
    "tags": ["approved"],
    // A review scoped to one requirement, with an already-settled comment
    "requirement": {
      "@path": "/Schemas/ClinicalTrial/1.0/Protocol",
      "label": "Study protocol",
    },
    "c1": {
      "@path": "/Submissions/demo-1/r2/c1",
      "sling:resourceType": "sub/ReviewComment",
      "author": "asmith",
      "text": "Formatting fixed",
      "resolved": true,
    },
  },
};

// A submission with none of the optional parts, but with attached documents: no title, no
// creation info, and an unexpanded (not dereferenced) schema version reference
const BARE_SUBMISSION = {
  "@path": "/Submissions/demo-2",
  "@name": "demo-2",
  "sling:resourceType": "sub/Submission",
  "tags": ["draft"],
  "schemaVersion": "f8cfa08e-b315-4eed-9d38-af6473fcd48f",
  "aliases": ["demo2", "second-demo"],
  "d1": {
    "@path": "/Submissions/demo-2/d1",
    "@name": "d1",
    "sling:resourceType": "sub/Document",
    "title": "Protocol document",
    "description": "The full protocol",
    "fulfills": {
      "@path": "/Schemas/ClinicalTrial/1.0/Protocol",
      "label": "Study protocol",
    },
    "protocol.pdf": {
      "@path": "/Submissions/demo-2/d1/protocol.pdf",
      "@name": "protocol.pdf",
      "jcr:primaryType": "nt:file",
      "contentType": "application/pdf",
      "size": 12345,
    },
    "consent #2 100%.pdf": {
      "@path": "/Submissions/demo-2/d1/consent #2 100%.pdf",
      "@name": "consent #2 100%.pdf",
      "jcr:primaryType": "nt:file",
      "contentType": "application/pdf",
      "size": 54321,
    },
  },
  "d2": {
    "@path": "/Submissions/demo-2/d2",
    "@name": "d2",
    "sling:resourceType": "sub/Document",
    // No title, description, requirement or files: everything optional is missing
  },
};

// The form projection as the SubmissionFormServlet would serve it, asking nothing: enough to tell
// the editor apart from the read-only page without restating what the editor's own tests cover.
const EMPTY_FORM = {
  path: "/Submissions/demo-1",
  title: "Test my drug",
  editable: true,
  requirements: [],
};

// Answers the tag definitions, the deep serialization and the form projection alike, so that a test
// can move between the page's two modes the way a reader does.
function bothModes(submission: unknown = DEEP_SUBMISSION) {
  const otherwise = tagAwareFetch(submission);
  return vi.fn<(url: string) => Promise<Response>>(url => url.endsWith(".form.json")
    ? Promise.resolve({ ok: true, json: () => Promise.resolve(EMPTY_FORM) } as unknown as Response)
    : otherwise(url));
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SubmissionView />
    </MemoryRouter>
  );
}

// The container SubmissionTasks asks for, holding one open step. Without it the page has no send
// control at all, and a test about whether sending is offered would assert nothing.
const WAITING = {
  timeOffRequest: {
    "sling:resourceType": "wf/WorkflowInstance",
    "@path": "/Submissions/demo-1/wf:instances/timeOffRequest",
    "fillIn": {
      "sling:resourceType": "wf/TaskInstance",
      "@path": "/Submissions/demo-1/wf:instances/timeOffRequest/fillIn",
      "status": "created",
      "label": "Send it",
      "outcomeOptions": [] as string[],
    },
  },
};

// The page's own fetch plus that container, so the send control is really on the page
function servingWithSendStep(submission: unknown) {
  return vi.fn((url: string) => url.includes("wf:instances")
    ? jsonResponse(WAITING)
    : tagAwareFetch(submission)(url));
}

describe("SubmissionView", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    clearTagDefinitionsCache();
  });

  it("displays the submission's answers, per the schema's structure, and its reviews", async () => {
    const fetchMock = vi.fn(tagAwareFetch(DEEP_SUBMISSION));
    vi.stubGlobal("fetch", fetchMock);

    renderAt("/Submissions/demo-1");

    // Header: title, the lifecycle tag chip, schema, creator
    expect(await screen.findByText("Test my drug")).toBeInTheDocument();
    expect(await screen.findByText("In review")).toBeInTheDocument();
    expect(screen.getByText(/ClinicalTrial 1.0/)).toBeInTheDocument();
    expect(screen.getByText(/by admin/)).toBeInTheDocument();

    // The submission itself was fetched with the deep serialization
    // Among the page's requests, not necessarily its first: the header asks what the request is
    // waiting for, and a child's effect runs before its parent's
    expect(fetchMock.mock.calls.map(call => call[0])).toContain("/Submissions/demo-1.deep.json");

    // The form requirement, its section, and its questions, with and without answers
    expect(screen.getByText("Basic information")).toBeInTheDocument();
    expect(screen.getByText("Contact details")).toBeInTheDocument();
    expect(screen.getByText("What is the full title of the study?")).toBeInTheDocument();
    expect(screen.getByText("A wonder drug against everything")).toBeInTheDocument();
    expect(screen.getByText("What is the contact email?")).toBeInTheDocument();
    expect(screen.getAllByText("Not answered yet").length).toBeGreaterThan(0);

    // Answer values are formatted per type: multi-values joined, booleans worded, numbers
    // stringified, and nested nodes treated as not answered
    expect(screen.getByText("pharmacology, Yes")).toBeInTheDocument();
    expect(screen.getByText("No")).toBeInTheDocument();
    expect(screen.getByText("36")).toBeInTheDocument();
    expect(screen.getAllByText("Not answered yet").length).toBe(2);

    // A question with no text falls back to its node name; non-question schema children are
    // skipped; a nested section shows its own description at a deeper level; untitled sections
    // and forms fall back to their node names
    expect(screen.getByText("Duration")).toBeInTheDocument();
    expect(screen.queryByText("Fill this form carefully")).toBeNull();
    expect(screen.getByText("Mailing address")).toBeInTheDocument();
    expect(screen.getByText("Where to send paper mail")).toBeInTheDocument();
    expect(screen.getByText("Fax")).toBeInTheDocument();
    expect(screen.getByText("ExtraForm")).toBeInTheDocument();

    // No documents attached, but the schema says one is expected
    expect(screen.getByText(/No documents attached yet/)).toBeInTheDocument();
    expect(screen.getByText(/expected: Study protocol/)).toBeInTheDocument();

    // The review with its threaded comment ("jdoe" appears as reviewer and as comment author);
    // its state is a review-category tag chip
    expect(screen.getAllByText("jdoe").length).toBeGreaterThan(0);
    expect(await screen.findByText("Changes requested")).toBeInTheDocument();
    expect(await screen.findByText("Approved")).toBeInTheDocument();
    expect(screen.getByText(/Please clarify the dosage/)).toBeInTheDocument();
    expect(screen.getByText(/Clarified in the summary/)).toBeInTheDocument();

    // The second review is scoped to a requirement, and its comment is marked resolved
    expect(screen.getByText("on Study protocol")).toBeInTheDocument();
    expect(screen.getByText(/Formatting fixed/)).toBeInTheDocument();
    expect(screen.getByText(/✓/)).toBeInTheDocument();
  });

  it("offers to send a request once nothing it asks for is missing", async () => {
    vi.stubGlobal("fetch", servingWithSendStep(DEEP_SUBMISSION));

    renderAt("/Submissions/demo-1");

    expect(await screen.findByRole("button", { name: /Send it/ })).toBeEnabled();
  });

  it("will not offer to send one that is still missing an answer", async () => {
    // The save workflow placed the tag, so the page knows without asking for the form
    vi.stubGlobal("fetch", servingWithSendStep({ ...DEEP_SUBMISSION, "tags": ["draft", "incomplete"] }));

    renderAt("/Submissions/demo-1");

    expect(await screen.findByRole("button", { name: /Send it/ })).toBeDisabled();
  });

  it("credits whoever raised the submission, not the engine that wrote it", async () => {
    // Every submission is written by the engine's service user, so jcr:createdBy names the engine; the
    // person it acted for is recorded as createdBy, and that is who the page has to say created it
    vi.stubGlobal("fetch", vi.fn(tagAwareFetch({ ...DEEP_SUBMISSION,
      "jcr:createdBy": "workflows", "createdBy": "demo-requester" })));

    renderAt("/Submissions/demo-1");

    expect(await screen.findByText(/by demo-requester/)).toBeInTheDocument();
  });

  it("falls back on who wrote it when nothing recorded who it was for", async () => {
    // Seeded content has no createdBy at all, and naming its writer is more use than naming nobody
    vi.stubGlobal("fetch", vi.fn(tagAwareFetch({ ...DEEP_SUBMISSION,
      "jcr:createdBy": "sling-jcr-content-loader" })));

    renderAt("/Submissions/demo-1");

    expect(await screen.findByText(/by sling-jcr-content-loader/)).toBeInTheDocument();
  });

  it("displays attached documents with download links, and minimal submissions without extras", async () => {
    vi.stubGlobal("fetch", vi.fn(tagAwareFetch(BARE_SUBMISSION)));

    renderAt("/Submissions/demo-2");

    // No title: the header falls back to the node name; no creation info line
    expect(await screen.findByRole("heading", { name: "demo-2" })).toBeInTheDocument();
    expect(screen.queryByText(/Created/)).toBeNull();
    expect(screen.queryByText(/Last modified/)).toBeNull();

    // The document with metadata: title, requirement, description, and a download link
    expect(screen.getByText(/Protocol document — fulfills "Study protocol"/)).toBeInTheDocument();
    expect(screen.getByText("The full protocol")).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "protocol.pdf" });
    expect(link).toHaveAttribute("href", "/Submissions/demo-2/d1/protocol.pdf");
    // File names containing URL syntax characters are percent-encoded, not truncated at the #
    const hostile = screen.getByRole("link", { name: "consent #2 100%.pdf" });
    expect(hostile).toHaveAttribute("href", "/Submissions/demo-2/d1/consent%20%232%20100%25.pdf");

    // The bare document falls back to its node name; the schema reference is not expanded, so
    // there are no forms and no expected-documents list; no reviews yet either
    expect(screen.getByText("d2")).toBeInTheDocument();
    expect(screen.getByText("No reviews yet")).toBeInTheDocument();
    expect(screen.queryByText(/expected:/)).toBeNull();
  });

  it("reports a fetch failure through its error message", async () => {
    vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(
      () => Promise.reject(new Error("network down"))));

    renderAt("/Submissions/demo-1");

    expect(await screen.findByText(/network down/)).toBeInTheDocument();
  });

  it("stringifies non-Error fetch rejections", async () => {
    vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(
      () => Promise.reject("catastrophe")));

    renderAt("/Submissions/demo-1");

    expect(await screen.findByText(/catastrophe/)).toBeInTheDocument();
  });

  it("ignores responses that arrive after the view is gone", async () => {
    const settlers: { resolve: (response: Response) => void; reject: (reason: unknown) => void }[] = [];
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>((resolve, reject) => {
      settlers.push({ resolve, reject });
    })));

    // A response landing after unmount must not update state
    const first = renderAt("/Submissions/demo-1");
    first.unmount();
    settlers[0].resolve({ ok: true, url: "", json: () => Promise.resolve(DEEP_SUBMISSION) } as unknown as Response);
    await act(() => Promise.resolve());

    // Same for a failure landing after unmount
    const second = renderAt("/Submissions/demo-1");
    second.unmount();
    settlers[1].reject(new Error("too late"));
    await act(() => Promise.resolve());

    expect(screen.queryByText("too late")).toBeNull();
  });

  it("reports an empty response as an undisplayable submission", async () => {
    vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(() => Promise.resolve(
      { ok: true, url: "", json: () => Promise.resolve(null) } as unknown as Response)));

    renderAt("/Submissions/demo-1");

    expect(await screen.findByText("This submission cannot be displayed")).toBeInTheDocument();
  });

  it("tolerates a .html suffix in the page URL", async () => {
    const fetchMock = vi.fn<(url: string) => Promise<Response>>(() => Promise.resolve(
      { ok: true, url: "", json: () => Promise.resolve(DEEP_SUBMISSION) } as unknown as Response));
    vi.stubGlobal("fetch", fetchMock);

    renderAt("/Submissions/demo-1.html");

    expect(await screen.findByText("Test my drug")).toBeInTheDocument();
    expect(fetchMock.mock.calls.map(call => call[0])).toContain("/Submissions/demo-1.deep.json");
  });

  it("reports inaccessible submissions", async () => {
    vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(
      () => Promise.resolve({ ok: false, url: "", status: 404 } as unknown as Response)));

    renderAt("/Submissions/nonexistent");

    // The shared vocabulary for a status, rather than wording this view invented for itself
    expect(await screen.findByText(/It could not be found on the server/)).toBeInTheDocument();
  });

  // The page's own share of the submit button: offering it in both modes, and doing the right
  // thing once it has been pressed
  describe("what the request is waiting for", () => {
    // The submission's own workflow, parked on a step with nothing to decide
    const WAITING = {
      timeOffRequest: {
        "@path": "/Submissions/demo-1/wf:instances/timeOffRequest",
        "sling:resourceType": "wf/WorkflowInstance",
        "fillIn": {
          "@path": "/Submissions/demo-1/wf:instances/timeOffRequest/fillIn",
          "sling:resourceType": "wf/TaskInstance",
          "label": "Say when you want to be away",
          "status": "created",
        },
      },
    };

    // The page as it really answers: its own serialization, the form projection, the tag
    // definitions, and — once the step has been completed — a workflow with nothing left waiting
    function withATaskWaiting() {
      const otherwise = bothModes();
      let done = false;
      return vi.fn<(url: string, init?: RequestInit) => Promise<Response>>((url, init) => {
        if (init?.method === "POST") {
          done = true;
          return Promise.resolve({ url, ok: true, json: () => Promise.resolve({}) } as unknown as Response);
        }
        if (url.includes("wf:instances")) {
          return Promise.resolve(
            { url, ok: true, json: () => Promise.resolve(done ? {} : WAITING) } as unknown as Response);
        }
        return otherwise(url).then(response => ({ ...response, url }));
      });
    }

    it("offers the waiting step while filling the request in, not only while reading it", async () => {
      vi.stubGlobal("fetch", withATaskWaiting());

      renderAt("/Submissions/demo-1.edit");

      expect(await screen.findByRole("button", { name: /Say when you want to be away/ })).toBeInTheDocument();
    });

    it("goes back to reading the request once the step is done, and reads it again", async () => {
      const fetchMock = withATaskWaiting();
      vi.stubGlobal("fetch", fetchMock);
      const user = userEvent.setup();
      renderAt("/Submissions/demo-1.edit");

      await user.click(await screen.findByRole("button", { name: /Say when you want to be away/ }));

      // Sending it has usually made it read-only, and what has changed is what the person now
      // wants to see — so the page shows it rather than leaving them in an editor that will refuse.
      // Asserted in the order it happens: the page changes mode straight away, and only then has
      // to read the submission again before it can show anything.
      expect(await screen.findByRole("button", { name: "View", pressed: true })).toBeInTheDocument();
      expect(await screen.findByText("Test my drug")).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: /Say when you want/ })).toBeNull();
    });
  });

  describe("switching between reading and filling in", () => {
    it("opens the editor, which asks the server what the form is", async () => {
      const fetchMock = bothModes();
      vi.stubGlobal("fetch", fetchMock);
      const user = userEvent.setup();
      renderAt("/Submissions/demo-1");
      await screen.findByText("Test my drug");

      await user.click(screen.getByRole("button", { name: "Edit" }));

      expect(await screen.findByText("This request asks nothing yet.")).toBeInTheDocument();
      expect(fetchMock.mock.calls.map(call => call[0])).toContain("/Submissions/demo-1.form.json");
    });

    // The editor used to be a dead end: reachable only from a listing, with nothing on it leading
    // anywhere. Going back also re-reads, since the editor saves as it goes.
    it("comes back out of the editor, reading the submission afresh", async () => {
      const fetchMock = bothModes();
      vi.stubGlobal("fetch", fetchMock);
      const user = userEvent.setup();
      renderAt("/Submissions/demo-1.edit");
      await screen.findByText("This request asks nothing yet.");

      await user.click(screen.getByRole("button", { name: "View" }));

      expect(await screen.findByText("Test my drug")).toBeInTheDocument();
      expect(fetchMock.mock.calls.map(call => call[0])).toContain("/Submissions/demo-1.deep.json");
    });

    it("says which of the two is showing", async () => {
      vi.stubGlobal("fetch", bothModes());
      renderAt("/Submissions/demo-1.edit");

      expect(await screen.findByRole("button", { name: "Edit", pressed: true })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "View", pressed: false })).toBeInTheDocument();
    });

    // An exclusive toggle group reports a deselection as null; there is no third mode to land in
    it("stays where it is when the mode already showing is chosen again", async () => {
      vi.stubGlobal("fetch", bothModes());
      const user = userEvent.setup();
      renderAt("/Submissions/demo-1");
      await screen.findByText("Test my drug");

      await user.click(screen.getByRole("button", { name: "View" }));

      expect(screen.getByText("Test my drug")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "View", pressed: true })).toBeInTheDocument();
    });

    // The states with nothing to show are the ones somebody most needs a way out of
    it("keeps the way out of a submission that cannot be loaded", async () => {
      vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(
        () => Promise.resolve({ ok: false, status: 403 } as unknown as Response)));

      renderAt("/Submissions/secret");

      expect(await screen.findByText(/You do not have permission to do this/)).toBeInTheDocument();
      expect(screen.getByRole("link", { name: /Back to the dashboard/ })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Edit" })).toBeInTheDocument();
    });
  });
});
