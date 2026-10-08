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

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { loadAnswerComponents } from "@iap/submissions/answers";
// Imported rather than fetched: in a browser each answer component arrives as its own asset, named
// by an extension on the AnswerComponent point, and registers itself as it is evaluated.
import "@iap/submissions/answers/BooleanAnswer";
import "@iap/submissions/answers/ChoiceAnswer";
import "@iap/submissions/answers/DateAnswer";
import "@iap/submissions/answers/FileAnswer";
import "@iap/submissions/answers/NumberAnswer";
import "@iap/submissions/answers/TextAnswer";
import SubmissionEditor from "@iap/submissions/SubmissionEditor";
import {
  CLASSIFICATION_REQUIREMENT,
  DOCUMENT_REQUIREMENT,
  type DocumentRequirement,
  FORM_REQUIREMENT,
  type FormItem,
  type FormRequirement,
  QUESTION,
  SECTION,
  type SubmissionForm,
} from "@iap/submissions/submissionForm";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(() => Promise.resolve([])),
}));

// Settles the load before the first render, so a field draws its input rather than the spinner it
// shows while the components are still on their way
beforeEach(async () => {
  await loadAnswerComponents();
});

const PATH = "/Submissions/ab/cd/ef/0a1b2c3d-0000-0000-0000-000000000000";

function duration(value: string[] = []) {
  return {
    name: "duration", type: QUESTION, path: "details/duration", text: "Is this several days?",
    dataType: "text", minAnswers: 1, maxAnswers: 1, options: [], value,
  };
}

// Typed as the subtype that holds questions, since the generic Requirement declares no items
function details(items: FormItem[], overrides: Partial<FormRequirement> = {}): FormRequirement {
  return {
    name: "details", path: "/Schemas/timeOffRequest/v1/details", type: FORM_REQUIREMENT, label: "Request details",
    items, ...overrides
  };
}

function endDate() {
  return {
    name: "endDate", type: QUESTION, path: "details/endDate", text: "Which day are you back?",
    dataType: "date", minAnswers: 1, maxAnswers: 1, options: [], value: [] as string[],
  };
}

/** The same question, pre-filled by the extraction and not yet settled by the submitter. */
function suggested() {
  return {
    ...duration([ "Yes" ]),
    provenance: {
      suggested: [ "Yes" ],
      confidence: 0.9,
      passages: [ { quote: "the leave runs over three days" } ],
      reviewed: false,
      evidenceRejected: false,
    },
  };
}

function form(overrides: Partial<SubmissionForm> = {}): SubmissionForm {
  return {
    path: PATH,
    title: "A long weekend",
    editable: true,
    readsDocuments: false,
    requirements: [ details([ duration() ], { description: "When and why." }) ],
    ...overrides,
  };
}

function json(body: unknown, init: { ok?: boolean; status?: number; url?: string } = {}) {
  return Promise.resolve({
    ok: init.ok ?? true,
    status: init.status ?? 200,
    // The authenticated fetch reads this to tell a form from a login page served with a 200
    url: init.url ?? "",
    json: () => Promise.resolve(body),
  } as unknown as Response);
}

// Serves each read of the form in turn, so a test can say what the server reports after a save.
function serving(...reads: SubmissionForm[]) {
  let read = 0;
  return vi.fn((url: string, options?: { method?: string }) => {
    if (options?.method === "POST") {
      return json({}, { url });
    }
    const next = reads[Math.min(read, reads.length - 1)];
    read += 1;
    return json(next, { url });
  });
}

describe("SubmissionEditor", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("shows what the request asks and what it already answers", async () => {
    vi.stubGlobal("fetch", serving(form()));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText("A long weekend")).toBeInTheDocument();
    expect(screen.getByText("Request details")).toBeInTheDocument();
    expect(screen.getByLabelText(/several days/)).toBeInTheDocument();
  });

  it("saves an answer and shows what the server then asks", async () => {
    // Which questions apply depends on the answers, and the server decides it. The editor finds out
    // by reading the form again: the return-date question simply appears in the next read.
    const withEndDate = form({
      requirements: [ details([ duration([ "multiple days" ]), endDate() ]) ],
    });
    const fetchMock = serving(form(), withEndDate);
    vi.stubGlobal("fetch", fetchMock);

    render(<SubmissionEditor path={PATH} />);
    await userEvent.type(await screen.findByLabelText(/several days/), "multiple days");
    await userEvent.tab();

    expect(await screen.findByLabelText(/Which day are you back/)).toBeInTheDocument();
    const posted = fetchMock.mock.calls.find(([ , options ]) => (options as { method?: string })?.method === "POST");
    expect(posted?.[0]).toBe(`${PATH}.save.json`);
  });

  it("records a confirmation as an event on the submission", async () => {
    const withProvenance = form({ requirements: [ details([ suggested() ]) ] });
    const fetchMock = serving(withProvenance);
    vi.stubGlobal("fetch", fetchMock);
    render(<SubmissionEditor path={PATH} />);
    await screen.findByText("AI found:");

    await userEvent.click(screen.getByRole("button", { name: "Confirm answer" }));

    const posted = fetchMock.mock.calls.find(([ , options ]) =>
      (options as { method?: string })?.method === "POST");
    expect(posted?.[0]).toBe(`${PATH}.reviewExtraction.json`);
    const body = (posted?.[1] as unknown as { body: URLSearchParams }).body;
    expect(body.get("question")).toBe("details/duration");
    expect(body.get("confirmed")).toBe("true");
  });

  // The answer is unchanged, so nothing about it should read as unsaved
  it("reports a refused review without claiming the answer failed to save", async () => {
    const withProvenance = form({ requirements: [ details([ suggested() ]) ] });
    const fetchMock = vi.fn((url: string, options?: { method?: string }) => options?.method === "POST"
      ? json({ error: "Nothing was extracted for that" }, { ok: false, status: 400 })
      : json(withProvenance));
    vi.stubGlobal("fetch", fetchMock);
    render(<SubmissionEditor path={PATH} />);
    await screen.findByText("AI found:");

    await userEvent.click(screen.getByRole("button", { name: "Confirm answer" }));

    expect(await screen.findByText("Review failed")).toBeInTheDocument();
    expect(screen.queryByText("Not saved")).not.toBeInTheDocument();
  });

  it("reports a refused save on the field it belongs to", async () => {
    // A save can be refused, because somebody submitted the request in another tab. The field is
    // where that has to show, since the rest of the form is untouched
    const fetchMock = vi.fn((url: string, options?: { method?: string }) => options?.method === "POST"
      ? json({ error: "This request has been submitted" }, { ok: false, status: 403 })
      : json(form()));
    vi.stubGlobal("fetch", fetchMock);

    render(<SubmissionEditor path={PATH} />);
    await userEvent.type(await screen.findByLabelText(/several days/), "half day");
    await userEvent.tab();

    expect(await screen.findByText("Not saved")).toBeInTheDocument();
  });

  it("tells the page the request changed, so the step that sends it can re-read", async () => {
    // The editor knowing the form again is not enough: what the request is still missing is recorded
    // on the submission, and the control offering to *send* it reads that. Without this, answering the
    // last question leaves that control refusing a request that is now complete.
    const changed = vi.fn();
    vi.stubGlobal("fetch", serving(form()));

    render(<SubmissionEditor path={PATH} onChanged={changed} />);
    // Finished, not merely typed into: an answer is saved when the field is left
    await userEvent.type(await screen.findByLabelText(/several days/), "multiple days");
    await userEvent.tab();

    await waitFor(() => expect(changed).toHaveBeenCalled());
  });

  it("says nothing to a page that did not ask to be told", async () => {
    // Optional, because the editor is renderable on its own and a page with no send control has
    // nothing to re-read
    vi.stubGlobal("fetch", serving(form()));

    render(<SubmissionEditor path={PATH} />);
    await userEvent.type(await screen.findByLabelText(/several days/), "multiple days");
    await userEvent.tab();

    expect(await screen.findByText("Saved")).toBeInTheDocument();
  });

  it("cannot be answered once the request is no longer the submitter's to change", async () => {
    vi.stubGlobal("fetch", serving(form({ editable: false })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText(/can no longer be changed/)).toBeInTheDocument();
    expect(screen.getByLabelText(/several days/)).toBeDisabled();
  });

  it("leaves approvals out, since the submitter has nothing to do there", async () => {
    // Where an approval stands is shown on the read-only page. In the editor a reviewer's step drawn
    // as a form section reads as something the submitter still has to fill in.
    vi.stubGlobal("fetch", serving(form({
      requirements: [ {
        name: "approval", path: "/Schemas/timeOffRequest/v1/approval", type: "sch/ApprovalRequirement",
        label: "Approval"
      } ],
    })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText("A long weekend")).toBeInTheDocument();
    expect(screen.queryByText("Approval")).toBeNull();
    expect(screen.queryByText(/Waiting for approval/)).toBeNull();
  });

  // One approval requirement, with whatever the projection is saying about it
  function approval(state: Record<string, unknown>) {
    return { name: "approval", type: "sch/ApprovalRequirement", label: "Approval", ...state };
  }

  describe("answering a document requirement", () => {
    const NOTE: DocumentRequirement = {
      name: "doctorsNote",
      path: "/Schemas/timeOffRequest/v1/doctorsNote",
      type: DOCUMENT_REQUIREMENT,
      label: "Doctor's note",
      description: "A note covering the days you were unwell.",
      required: true,
      acceptedFileTypes: [ ".doc", "application/pdf" ],
      template: "/Schemas/timeOffRequest/v1/doctorsNote/template",
      templateName: "Doctor's note.docx",
      attached: [],
    };

    const NOTE_FILE = { title: "note.pdf", path: "/Submissions/x/d1/v1/file/uploadedFile" };

    function asked(note: Partial<DocumentRequirement> = {}, overrides: Partial<SubmissionForm> = {}) {
      return form({ requirements: [ { ...NOTE, ...note } ], ...overrides });
    }

    // A .doc, because the browser-side check now looks inside the file and a legacy Word document is
    // recognised by eight bytes rather than by a library these tests would have to stand in for.
    const OLE_MAGIC = new Uint8Array([ 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1 ]);

    function pick(name = "note.doc", type = "application/msword") {
      return new File([ OLE_MAGIC ], name, { type });
    }

    it("offers to attach a file, with the types it takes and nothing else", async () => {
      vi.stubGlobal("fetch", serving(asked()));

      render(<SubmissionEditor path={PATH} />);

      const input = await screen.findByLabelText(/Attach a file for "Doctor's note"/);
      expect(input).toHaveAttribute("accept", "application/pdf,image/png");
      expect(screen.queryByText(/Nothing attached yet/)).toBeNull();
      expect(screen.queryByRole("link")).toBeNull();
    });

    it("says nothing about types where the requirement names none", async () => {
      vi.stubGlobal("fetch", serving(asked({ acceptedFileTypes: [] })));

      render(<SubmissionEditor path={PATH} />);

      expect(await screen.findByLabelText(/Attach a file/)).not.toHaveAttribute("accept");
    });

    it("names what is already there, so a form reopened later does not look untouched", async () => {
      vi.stubGlobal("fetch", serving(asked({ attached: [ NOTE_FILE ] })));

      render(<SubmissionEditor path={PATH} />);

      const link = await screen.findByRole("link", { name: "note.pdf" });
      expect(link).toHaveAttribute("href", NOTE_FILE.path);
      expect(link).toHaveAttribute("download", "note.pdf");
      expect(screen.queryByText("Nothing attached yet")).toBeNull();
    });

    it("names an attachment with no file yet without linking it", async () => {
      vi.stubGlobal("fetch", serving(asked({ attached: [ { title: "note.pdf" } ] })));

      render(<SubmissionEditor path={PATH} />);

      expect(await screen.findByText(/note\.pdf/)).toBeInTheDocument();
      expect(screen.queryByRole("link", { name: "note.pdf" })).toBeNull();
    });

    it("offers only removal while a file is attached", async () => {
      vi.stubGlobal("fetch", serving(asked({ attached: [ NOTE_FILE ] })));

      render(<SubmissionEditor path={PATH} />);

      expect(await screen.findByRole("button", { name: "Remove" })).toBeInTheDocument();
      expect(screen.queryByLabelText(/Attach a file/)).toBeNull();
      expect(screen.queryByLabelText(/Replace the file/)).toBeNull();
    });

    it("posts the file as an event on the submission, then reads the form again", async () => {
      const fetchMock = serving(asked(), asked({ attached: [ NOTE_FILE ] }));
      vi.stubGlobal("fetch", fetchMock);

      render(<SubmissionEditor path={PATH} />);
      await userEvent.upload(await screen.findByLabelText(/Attach a file/), pick());

      const upload = fetchMock.mock.calls.find(call => call[0] === `${PATH}.attachDocument.json`);
      expect(upload).toBeDefined();
      const init = upload![1] as RequestInit;
      expect(init.method).toBe("POST");
      const body = init.body as FormData;
      expect(body.get("requirement")).toBe("doctorsNote");
      expect((body.get("file") as File).name).toBe("note.doc");
      // No Content-Type of our own: only the browser knows the multipart boundary it generated
      expect(init.headers).toBeUndefined();
      // What the server now says is attached, rather than what this page hoped
      expect(await screen.findByRole("link", { name: "note.pdf" })).toBeInTheDocument();
    });

    it("removes an attached file as an event on the submission, then reads the form again", async () => {
      const fetchMock = serving(asked({ attached: [ NOTE_FILE ] }), asked());
      vi.stubGlobal("fetch", fetchMock);

      render(<SubmissionEditor path={PATH} />);
      await userEvent.click(await screen.findByRole("button", { name: "Remove" }));

      const detach = fetchMock.mock.calls.find(call => call[0] === `${PATH}.detachDocument.json`);
      expect(detach).toBeDefined();
      const init = detach![1] as RequestInit;
      expect(init.method).toBe("POST");
      expect((init.body as URLSearchParams).get("requirement")).toBe("doctorsNote");
      expect(await screen.findByLabelText(/Attach a file/)).toBeInTheDocument();
      expect(screen.queryByRole("link", { name: "note.pdf" })).toBeNull();
    });

    it("does not offer to remove a file from a request that can no longer be changed", async () => {
      vi.stubGlobal("fetch", serving(asked({ attached: [ NOTE_FILE ] }, { editable: false })));

      render(<SubmissionEditor path={PATH} />);

      expect(await screen.findByRole("button", { name: "Remove" })).toBeDisabled();
    });

    // Removed mid-reading, the reading would wait for a parse that lands on nothing
    it("does not offer to remove a file while the documents are being read", async () => {
      vi.stubGlobal("fetch", serving(asked({ attached: [ "note.doc" ] }, { extraction: { status: "running" } })));

      render(<SubmissionEditor path={PATH} />);

      const remove = await screen.findByRole("button", { name: "Remove" });
      expect(remove).toBeDisabled();
      expect(remove).toHaveAttribute("title", expect.stringMatching(/Wait until the document has been read/));
    });

    it("names a requirement with no label by its name", async () => {
      vi.stubGlobal("fetch", serving(asked({ label: "" })));

      render(<SubmissionEditor path={PATH} />);

      expect(await screen.findByLabelText(/Attach a file for "doctorsNote"/)).toBeInTheDocument();
    });

    it("says why a file could not be removed", async () => {
      let refusal: unknown = { error: "This request is already sent" };
      vi.stubGlobal("fetch", vi.fn((url: string, options?: { method?: string }) =>
        options?.method === "POST"
          ? json(refusal, { ok: false, status: 409 })
          : json(asked({ attached: [ NOTE_FILE ] }))));

      render(<SubmissionEditor path={PATH} />);
      await userEvent.click(await screen.findByRole("button", { name: "Remove" }));
      expect(await screen.findByText("This request is already sent")).toBeInTheDocument();

      refusal = {};
      await userEvent.click(screen.getByRole("button", { name: "Remove" }));
      expect(await screen.findByText("This file could not be removed (409)")).toBeInTheDocument();
    });

    it("says why the engine refused a file, in the engine's own words", async () => {
      // A file the browser-side check is happy with, so the request really does reach the server.
      // What the server then refuses, it refuses on its own reading of the request, and that reason
      // is the one worth showing.
      vi.stubGlobal("fetch", vi.fn((url: string, options?: { method?: string }) =>
        options?.method === "POST"
          ? json({ error: "This request no longer takes documents" }, { ok: false, status: 400 })
          : json(asked({ acceptedFileTypes: [] }))));

      render(<SubmissionEditor path={PATH} />);
      await userEvent.upload(await screen.findByLabelText(/Attach a file/), pick());

      expect(await screen.findByText("This request no longer takes documents")).toBeInTheDocument();
    });

    // The browser-side check, which is there so a person finds out at once rather than after a slow
    // upload. The server checks again; this only saves the wait.
    it("refuses a file it can see is wrong before sending it", async () => {
      const fetchMock = serving(asked({ acceptedFileTypes: [] }));
      vi.stubGlobal("fetch", fetchMock);

      render(<SubmissionEditor path={PATH} />);
      await userEvent.upload(await screen.findByLabelText(/Attach a file/),
        new File([ "not a document" ], "scan.gif", { type: "image/gif" }));

      expect(await screen.findByText(/scan.gif is not a/)).toBeInTheDocument();
      expect(fetchMock.mock.calls.some(([ , options ]) =>
        (options as { method?: string })?.method === "POST")).toBe(false);
    });

    it("falls back on its own words when the refusal carries none", async () => {
      vi.stubGlobal("fetch", vi.fn((url: string, options?: { method?: string }) =>
        options?.method === "POST"
          ? Promise.resolve({
            ok: false,
            status: 502,
            url: "",
            json: () => Promise.reject(new Error("not json")),
          } as unknown as Response)
          : json(asked())));

      render(<SubmissionEditor path={PATH} />);
      await userEvent.upload(await screen.findByLabelText(/Attach a file/), pick());

      expect(await screen.findByText(/This file could not be attached \(502\)/)).toBeInTheDocument();
    });

    it("reports an upload that never reached the server", async () => {
      vi.stubGlobal("fetch", vi.fn((url: string, options?: { method?: string }) =>
        options?.method === "POST"
          ? Promise.reject("the network went away")
          : json(asked())));

      render(<SubmissionEditor path={PATH} />);
      await userEvent.upload(await screen.findByLabelText(/Attach a file/), pick());

      expect(await screen.findByText("the network went away")).toBeInTheDocument();
    });

    it("lets a refusal be dismissed and the same file tried again", async () => {
      let refuse = true;
      vi.stubGlobal("fetch", vi.fn((url: string, options?: { method?: string }) => {
        if (options?.method === "POST") {
          const answer = refuse
            ? json({ error: "The server was busy" }, { ok: false, status: 503 })
            : json({});
          refuse = false;
          return answer;
        }
        return json(asked());
      }));

      render(<SubmissionEditor path={PATH} />);
      const input = await screen.findByLabelText(/Attach a file/);
      await userEvent.upload(input, pick());
      await screen.findByText("The server was busy");
      // Cleared after each pick, so that the same file counts as a change and can be retried. A test
      // environment fires the change whatever the value, which is why this is asserted outright.
      expect(input).toHaveValue("");
      await userEvent.click(screen.getByRole("button", { name: /Close/ }));

      await userEvent.upload(input, pick());

      await waitFor(() => expect(screen.queryByText("The server was busy")).toBeNull());
    });

    it("has the browser warn before a page with an upload on its way is left", async () => {
      let land: () => void = () => undefined;
      vi.stubGlobal("fetch", vi.fn((url: string, options?: { method?: string }) =>
        options?.method === "POST"
          ? new Promise<Response>(resolve => {
            land = () => resolve(json({}));
          })
          : json(asked())));
      const leave = () => {
        const event = new Event("beforeunload", { cancelable: true });
        window.dispatchEvent(event);
        return event.defaultPrevented;
      };

      render(<SubmissionEditor path={PATH} />);
      const input = await screen.findByLabelText(/Attach a file/);
      expect(leave()).toBe(false);

      await userEvent.upload(input, pick());
      expect(leave()).toBe(true);

      land();
      await waitFor(() => expect(leave()).toBe(false));
    });

    it("does nothing when the file dialog was dismissed without a choice", async () => {
      const fetchMock = serving(asked());
      vi.stubGlobal("fetch", fetchMock);

      render(<SubmissionEditor path={PATH} />);
      // What a cancelled dialog looks like to the change handler, which `userEvent.upload` cannot
      // express: it always has a file to give
      fireEvent.change(await screen.findByLabelText(/Attach a file/), { target: { files: [] } });

      expect(fetchMock.mock.calls.some(call => call[0] === `${PATH}.attachDocument.json`)).toBe(false);
    });

    it("tells the page the request changed when a document is attached", async () => {
      // Attaching the last thing a request was waiting for makes it ready to send, which is the same
      // chain a saved answer walks
      const changed = vi.fn();
      vi.stubGlobal("fetch", serving(asked(), asked({ attached: [ "note.doc" ] })));

      render(<SubmissionEditor path={PATH} onChanged={changed} />);
      await userEvent.upload(await screen.findByLabelText(/Attach a file/), pick());

      await waitFor(() => expect(changed).toHaveBeenCalled());
    });

    it("cannot be attached to once the request is no longer the submitter's to change", async () => {
      // The same field that disables the questions, so the control cannot outlive the permission
      vi.stubGlobal("fetch", serving(asked({}, { editable: false })));

      render(<SubmissionEditor path={PATH} />);

      expect(await screen.findByLabelText(/Attach a file/)).toBeDisabled();
    });
  });

  it("draws a section as its own block, with its questions inside", async () => {
    vi.stubGlobal("fetch", serving(form({
      requirements: [ details([ { name: "when", type: SECTION, label: "Dates",
        description: "When you are away", items: [ endDate() ] } ]) ],
    })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText("Dates")).toBeInTheDocument();
    expect(screen.getByText("When you are away")).toBeInTheDocument();
    expect(screen.getByLabelText(/Which day are you back/)).toBeInTheDocument();
  });

  // A step under a form section is how its open questions get answered, so the page's "not ready to
  // send" reason must not hide it: a request with open questions always has that reason.
  it("offers a form section's own step even while the request cannot be sent", async () => {
    const instances = {
      reading: {
        "@path": `${PATH}/wf:instances/reading`,
        "sling:resourceType": "wf/WorkflowInstance",
        "task": {
          "sling:resourceType": "wf/TaskInstance",
          "@path": `${PATH}/wf:instances/reading/task`,
          "label": "Read the questions for this kind of study",
          "status": "created",
          "@mine": true,
          "requirement": "details",
        },
      },
    };
    vi.stubGlobal("fetch", vi.fn((url: string) =>
      json(url.includes("wf:instances") ? instances : form())));

    render(<SubmissionEditor path={PATH} blockedReason="Answer everything this request asks for before sending it." />);

    expect(await screen.findByRole("button", { name: /Read the questions for this kind of study/ }))
      .toBeEnabled();
  });

  it("falls back on the name when a requirement or a section is unlabelled", async () => {
    // The projection always carries a label and empties it rather than omitting it
    // (`Objects.toString(getLabel(), "")`), so this is what an unlabelled block arrives as. It still
    // has to be identifiable rather than headed by nothing
    vi.stubGlobal("fetch", serving(form({
      requirements: [ details(
        [ { name: "when", type: SECTION, label: "", items: [ endDate() ] } ], { label: "" }) ],
    })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText("details")).toBeInTheDocument();
    expect(screen.getByText("when")).toBeInTheDocument();
  });

  it("reports a save that failed without an Error to explain it", async () => {
    // A rejection is not necessarily an Error, since a thrown string reaches the same handler, and
    // the field still has to say what happened rather than "undefined"
    const fetchMock = vi.fn((url: string, options?: { method?: string }) => options?.method === "POST"
      ? Promise.reject("the request went nowhere")
      : json(form()));
    vi.stubGlobal("fetch", fetchMock);

    render(<SubmissionEditor path={PATH} />);
    await userEvent.type(await screen.findByLabelText(/several days/), "half day");
    await userEvent.tab();

    const failure = await screen.findByText("Not saved");
    fireEvent.mouseOver(failure);
    expect(await screen.findByRole("tooltip")).toHaveTextContent("the request went nowhere");
  });

  it("says so when the request asks nothing", async () => {
    vi.stubGlobal("fetch", serving(form({ requirements: [] })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText(/asks nothing yet/)).toBeInTheDocument();
  });

  it("reports a form that would not load", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json({}, { ok: false, status: 404 })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText(/could not be found on the server/)).toBeInTheDocument();
  });

  it("says the server sent a page when the form arrives as HTML", async () => {
    // Sling answers an expired session with a 200 login page. Parsing that as JSON used to show
    // "Unexpected token '<'", which names the parser rather than the problem.
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({
      ok: true,
      status: 200,
      url: "/form.json",
      json: () => Promise.reject(new SyntaxError("Unexpected token '<', \"<!doctype \"... is not valid JSON")),
    } as unknown as Response)));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText(/sent a page instead of data/)).toBeInTheDocument();
    expect(screen.queryByText(/Unexpected token/)).toBeNull();
  });

  it("keeps the newest answer when two are finished in quick succession", async () => {
    let resolveFirst: (value: Response) => void = () => {};
    const first = new Promise<Response>(resolve => {
      resolveFirst = resolve;
    });
    let reads = 0;
    const fetchMock = vi.fn((url: string, options?: { method?: string }) => {
      if (options?.method === "POST") {
        return json({});
      }
      reads += 1;
      // The first read after a save is held back until the second has already been applied
      return reads === 2 ? first : json(form({ title: "Newest" }));
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<SubmissionEditor path={PATH} />);
    await userEvent.type(await screen.findByLabelText(/several days/), "half day");
    await userEvent.tab();
    await userEvent.type(screen.getByLabelText(/several days/), " really");
    await userEvent.tab();

    await waitFor(() => expect(screen.getByText("Newest")).toBeInTheDocument());
    resolveFirst(await json(form({ title: "Stale" })));

    // The overtaken read is dropped rather than applied over the newer one
    await waitFor(() => expect(screen.queryByText("Stale")).not.toBeInTheDocument());
    expect(screen.getByText("Newest")).toBeInTheDocument();
  });

  it("turns to the classification, then the answers the model fills in, with Next, and starts nothing", async () => {
    const proposal = {
      name: "proposal", type: DOCUMENT_REQUIREMENT, label: "Research proposal",
      required: true, acceptedFileTypes: [ "application/pdf" ], attached: [ "protocol.pdf" ],
    };
    const classification = {
      name: "is_proposal", type: CLASSIFICATION_REQUIREMENT, label: "Is this a research proposal?",
      extracted: true, items: [ duration([ "Yes" ]) ],
    };
    const study = {
      name: "common", type: FORM_REQUIREMENT, label: "The study", extracted: true,
      items: [ duration() ],
    };
    const admin = {
      name: "administrative", type: FORM_REQUIREMENT, label: "Administrative information", extracted: false,
      items: [ { ...endDate(), value: [ "2026-10-06" ] } ],
    };
    const fetchMock = vi.fn((url: string, options?: { method?: string }) => {
      if (options?.method === "POST") {
        return json({}, { url });
      }
      return json(url.includes("wf:instances") ? {} : form({
        readsDocuments: true,
        requirements: [ proposal, admin, classification, study ],
      }), { url });
    });
    vi.stubGlobal("fetch", fetchMock);

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText("Research proposal")).toBeInTheDocument();
    expect(screen.getByText("Administrative information")).toBeInTheDocument();
    expect(screen.queryByText("Is this a research proposal?")).toBeNull();
    expect(screen.queryByText("The study")).toBeNull();

    await userEvent.click(screen.getByRole("button", { name: "Next" }));

    expect(await screen.findByText("Is this a research proposal?")).toBeInTheDocument();
    expect(screen.queryByText("Research proposal")).toBeNull();
    expect(screen.queryByText("The study")).toBeNull();

    await userEvent.click(screen.getByRole("button", { name: "Next" }));

    expect(await screen.findByText("The study")).toBeInTheDocument();
    expect(screen.queryByText("Is this a research proposal?")).toBeNull();
    expect(fetchMock.mock.calls.some(([ , options ]) => options?.method === "POST")).toBe(false);

    await userEvent.click(screen.getByRole("button", { name: "Back" }));
    expect(screen.getByText("Is this a research proposal?")).toBeInTheDocument();
    expect(screen.queryByText("The study")).toBeNull();

    await userEvent.click(screen.getByRole("button", { name: "Back" }));
    expect(screen.getByText("Research proposal")).toBeInTheDocument();
    expect(screen.queryByText("Is this a research proposal?")).toBeNull();
  });

  it("holds Next back until a required document is attached", async () => {
    const proposal = {
      name: "proposal", type: DOCUMENT_REQUIREMENT, label: "Research proposal",
      required: true, acceptedFileTypes: [ "application/pdf" ], attached: [] as string[],
    };
    vi.stubGlobal("fetch", vi.fn((url: string) => json(url.includes("wf:instances") ? {} : form({
      readsDocuments: true,
      requirements: [ proposal ],
    }), { url })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByRole("button", { name: "Next" })).toBeDisabled();
  });

  it("asks a classification on the first page, and holds Next back until it is answered", async () => {
    const proposal = {
      name: "proposal", type: DOCUMENT_REQUIREMENT, label: "Research proposal",
      required: true, acceptedFileTypes: [ "application/pdf" ], attached: [ "protocol.pdf" ],
    };
    const classification = {
      name: "is_proposal", type: CLASSIFICATION_REQUIREMENT, label: "Is this a research proposal?",
      extracted: false, items: [ duration() ],
    };
    vi.stubGlobal("fetch", vi.fn((url: string) => json(url.includes("wf:instances") ? {} : form({
      readsDocuments: true,
      requirements: [ proposal, classification ],
    }), { url })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText("Is this a research proposal?")).toBeInTheDocument();
    expect(screen.getByLabelText(/several days/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
  });

  it("holds Next back until every required question on the page is answered", async () => {
    const proposal = {
      name: "proposal", type: DOCUMENT_REQUIREMENT, label: "Research proposal",
      required: true, acceptedFileTypes: [ "application/pdf" ], attached: [ "protocol.pdf" ],
    };
    vi.stubGlobal("fetch", vi.fn((url: string) => json(form({
      readsDocuments: true,
      requirements: [ proposal,
        details([ duration() ], { name: "screening", label: "About this questionnaire", extracted: false }) ],
    }), { url })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByRole("button", { name: "Next" })).toBeDisabled();
  });
});
