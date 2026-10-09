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

import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";

import SubmissionView from "@iap/submissions/SubmissionView";
import { clearTagDefinitionsCache } from "@iap/tags/tagDefinitions";
import { tagAwareFetch } from "@iap/tags/tagDefinitions.fixture";

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
    "v1": version("/Submissions/demo-2/d1", "v1"),
    "v2": version("/Submissions/demo-2/d1", "v2"),
  },
  "d2": {
    "@path": "/Submissions/demo-2/d2",
    "@name": "d2",
    "sling:resourceType": "sub/Document",
    // No title, description, requirement or files: everything optional is missing
  },
};

// A document version as the deep serialization shows it, holding one upload.
function version(documentPath: string, name: string) {
  return {
    "@path": `${documentPath}/${name}`,
    "@name": name,
    "sling:resourceType": "sub/DocumentVersion",
    "file": {
      "@path": `${documentPath}/${name}/file`,
      "@name": "file",
      "sling:resourceType": "sub/File",
      "uploadedFile": {
        "@path": `${documentPath}/${name}/file/uploadedFile`,
        "@name": "uploadedFile",
        "jcr:primaryType": "nt:file",
        "jcr:mimeType": "application/pdf",
      },
    },
  };
}

// The form projection as the SubmissionFormServlet would serve it, asking nothing. Enough to tell
// the editor apart from the read-only page, without restating what the editor's own tests cover.
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
    ? Promise.resolve({ ok: true, url: "", json: () => Promise.resolve(EMPTY_FORM) } as unknown as Response)
    : otherwise(url));
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SubmissionView />
    </MemoryRouter>
  );
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
    expect(fetchMock.mock.calls[0][0]).toBe("/Submissions/demo-1.deep.json");

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

    // Four fallbacks at once. A question with no text takes its node name, non-question schema
    // children are skipped, a nested section shows its description a level deeper, and untitled
    // sections and forms take their node names
    expect(screen.getByText("Duration")).toBeInTheDocument();
    expect(screen.queryByText("Fill this form carefully")).toBeNull();
    expect(screen.getByText("Mailing address")).toBeInTheDocument();
    expect(screen.getByText("Where to send paper mail")).toBeInTheDocument();
    expect(screen.getByText("Fax")).toBeInTheDocument();
    expect(screen.getByText("ExtraForm")).toBeInTheDocument();

    // The documents section asks the server what this request is being asked for, which this
    // fixture does not answer; what it asks for is its own group of tests
    expect(screen.getByText("This request asks for no documents")).toBeInTheDocument();

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

  it("displays attached documents with download links, and minimal submissions without extras", async () => {
    vi.stubGlobal("fetch", vi.fn(tagAwareFetch(BARE_SUBMISSION)));

    renderAt("/Submissions/demo-2");

    // No title: the header falls back to the node name; no creation info line
    expect(await screen.findByRole("heading", { name: "demo-2" })).toBeInTheDocument();
    expect(screen.queryByText(/Created/)).toBeNull();
    expect(screen.queryByText(/Last modified/)).toBeNull();

    // The document with metadata: title, requirement, description, and a link to its newest file,
    // saved under the title since the stored file is always called `uploadedFile`
    expect(await screen.findByText(/— fulfills "Study protocol"/)).toBeInTheDocument();
    expect(screen.getByText("The full protocol")).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "Protocol document" });
    expect(link).toHaveAttribute("href", "/Submissions/demo-2/d1/v2/file/uploadedFile");
    expect(link).toHaveAttribute("download", "Protocol document");
    expect(screen.getAllByRole("link", { name: "Protocol document" })).toHaveLength(1);

    // The bare document falls back to its node name; the schema reference is not expanded, so
    // there are no forms; no reviews yet either
    expect(screen.getByText("d2")).toBeInTheDocument();
    expect(screen.getByText("No reviews yet")).toBeInTheDocument();
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
    expect(fetchMock.mock.calls[0][0]).toBe("/Submissions/demo-1.deep.json");
  });

  it("reports inaccessible submissions", async () => {
    vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(
      () => Promise.resolve({ ok: false, url: "", status: 404 } as unknown as Response)));

    renderAt("/Submissions/nonexistent");

    // The shared vocabulary for a status, rather than wording this view invented for itself
    expect(await screen.findByText(/It could not be found on the server/)).toBeInTheDocument();
  });

  describe("the documents section", () => {
    const PROTOCOL = {
      name: "Protocol",
      path: "/Schemas/ClinicalTrial/1.0/Protocol",
      type: "sch/DocumentRequirement",
      label: "Study protocol",
      description: "The full protocol, signed",
      required: true,
      acceptedFileTypes: ["application/pdf"],
      attached: [] as string[],
    };

    function formResponse(form: unknown) {
      return { ok: true, url: "", json: () => Promise.resolve(form) } as unknown as Response;
    }

    function serving(form: unknown, submission: unknown = DEEP_SUBMISSION) {
      const otherwise = tagAwareFetch(submission);
      return vi.fn<(url: string) => Promise<Response>>(url => url.endsWith(".form.json")
        ? Promise.resolve(formResponse(form))
        : otherwise(url));
    }

    function projection(requirements: unknown[] = [PROTOCOL]) {
      return { ...EMPTY_FORM, requirements };
    }

    function attachment(title: string, extra: Record<string, unknown> = {}) {
      return {
        "@path": "/Submissions/demo-1/d1",
        "@name": "d1",
        "sling:resourceType": "sub/Document",
        "title": title,
        "fulfills": { "@path": "/Schemas/ClinicalTrial/1.0/Protocol", "@name": "Protocol" },
        ...extra,
      };
    }

    it("lists what the request is being asked for, and says nothing answers it yet", async () => {
      // From the projection rather than from the schema this page already holds: a document
      // requirement can be conditional, and conditions are resolved on the server
      vi.stubGlobal("fetch", serving(projection()));

      renderAt("/Submissions/demo-1");

      expect(await screen.findByRole("heading", { name: "Study protocol" })).toBeInTheDocument();
      expect(screen.getByText("The full protocol, signed")).toBeInTheDocument();
      expect(screen.getByText("Nothing attached yet")).toBeInTheDocument();
    });

    it("says when a document nobody has attached was optional", async () => {
      vi.stubGlobal("fetch", serving(projection([{ ...PROTOCOL, required: false }])));

      renderAt("/Submissions/demo-1");

      expect(await screen.findByText("Nothing attached yet — optional")).toBeInTheDocument();
    });

    it("never offers to attach one, whoever is reading", async () => {
      // Attaching belongs to the editor, so there is only ever one control for it
      vi.stubGlobal("fetch", serving(projection()));

      renderAt("/Submissions/demo-1");

      expect(await screen.findByRole("heading", { name: "Study protocol" })).toBeInTheDocument();
      expect(screen.queryByLabelText(/Attach a file/)).toBeNull();
    });

    it("names a requirement by its node name when it carries no label", async () => {
      vi.stubGlobal("fetch", serving(projection([{ ...PROTOCOL, label: "" }])));

      renderAt("/Submissions/demo-1");

      expect(await screen.findByRole("heading", { name: "Protocol" })).toBeInTheDocument();
    });

    it("shows a requirement that says nothing beyond its name", async () => {
      vi.stubGlobal("fetch", serving(projection([{ ...PROTOCOL, description: undefined }])));

      renderAt("/Submissions/demo-1");

      expect(await screen.findByRole("heading", { name: "Study protocol" })).toBeInTheDocument();
      expect(screen.queryByText("The full protocol, signed")).toBeNull();
    });

    it("groups an attached document under the requirement it answers", async () => {
      const answered = {
        ...DEEP_SUBMISSION,
        d1: attachment("protocol.pdf", { v1: version("/Submissions/demo-1/d1", "v1") }),
      };
      vi.stubGlobal("fetch", serving(projection(), answered));

      renderAt("/Submissions/demo-1");

      // The requirement first: what is attached comes from the submission and what it answers comes
      // from the projection, so the grouping is only settled once both have arrived
      expect(await screen.findByRole("heading", { name: "Study protocol" })).toBeInTheDocument();
      expect(await screen.findByRole("link", { name: "protocol.pdf" })).toBeInTheDocument();
      await waitFor(() => expect(screen.queryByText("Nothing attached yet")).toBeNull());
      // The grouping already says which requirement it answers, so the document does not repeat it
      expect(screen.queryByText(/fulfills/)).toBeNull();
    });

    it("does not group a document under a requirement that only shares its name", async () => {
      // The same name in another schema version is a different requirement
      const elsewhere = attachment("old.pdf", {
        fulfills: { "@path": "/Schemas/ClinicalTrial/0.9/Protocol", "@name": "Protocol" },
      });
      vi.stubGlobal("fetch", serving(projection(), { ...DEEP_SUBMISSION, d1: elsewhere }));

      renderAt("/Submissions/demo-1");

      expect(await screen.findByText("old.pdf")).toBeInTheDocument();
      expect(screen.getByText("Nothing attached yet")).toBeInTheDocument();
    });

    it("still shows a document whose requirement no longer applies", async () => {
      // A condition that held when the file was attached, and no longer does: the requirement is
      // gone from the projection, but the file is still somebody's evidence
      vi.stubGlobal("fetch", serving(projection([]), { ...DEEP_SUBMISSION, d1: attachment("note.pdf") }));

      renderAt("/Submissions/demo-1");

      expect(await screen.findByText("note.pdf")).toBeInTheDocument();
    });

    it("says so when a request asks for no documents and holds none", async () => {
      vi.stubGlobal("fetch", serving(projection([])));

      renderAt("/Submissions/demo-1");

      expect(await screen.findByText("This request asks for no documents")).toBeInTheDocument();
    });

    it("says what was asked could not be read, rather than that nothing was", async () => {
      vi.spyOn(console, "error").mockImplementation(() => undefined);
      const otherwise = tagAwareFetch({ ...DEEP_SUBMISSION, d1: attachment("note.pdf") });
      vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(url => url.endsWith(".form.json")
        ? Promise.reject(new Error("no projection"))
        : otherwise(url)));

      renderAt("/Submissions/demo-1");

      expect(await screen.findByText(/Which documents this request asks for could not be read/))
        .toBeInTheDocument();
      // What is attached comes from the submission itself, so it is still shown
      expect(screen.getByText("note.pdf")).toBeInTheDocument();
      expect(screen.queryByText("This request asks for no documents")).toBeNull();
    });

    it("says nothing about what was asked while the projection is on its way", async () => {
      const otherwise = tagAwareFetch(DEEP_SUBMISSION);
      vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(url => url.endsWith(".form.json")
        ? new Promise<Response>(() => undefined)
        : otherwise(url)));

      renderAt("/Submissions/demo-1");

      expect(await screen.findByRole("progressbar", { name: "Loading the documents" })).toBeInTheDocument();
      expect(screen.queryByText("This request asks for no documents")).toBeNull();
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

    it("stays where it is when the mode already showing is chosen again", async () => {
      vi.stubGlobal("fetch", bothModes());
      const user = userEvent.setup();
      renderAt("/Submissions/demo-1");
      await screen.findByText("Test my drug");

      await user.click(screen.getByRole("button", { name: "View" }));

      expect(screen.getByText("Test my drug")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "View", pressed: true })).toBeInTheDocument();
    });

    it("keeps the way out of a submission that cannot be loaded", async () => {
      vi.stubGlobal("fetch", vi.fn<(url: string) => Promise<Response>>(
        () => Promise.resolve({ ok: false, url: "", status: 403 } as unknown as Response)));

      renderAt("/Submissions/secret");

      expect(await screen.findByText(/You do not have permission to do this/)).toBeInTheDocument();
      expect(screen.getByRole("link", { name: /Back to the dashboard/ })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Edit" })).toBeInTheDocument();
    });
  });
});
