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

import SubmissionEditor from "@iap/submissions/SubmissionEditor";
import {
  DOCUMENT_REQUIREMENT,
  FORM_REQUIREMENT,
  QUESTION,
  SECTION,
  type SubmissionForm,
} from "@iap/submissions/submissionForm";

const PATH = "/Submissions/ab/cd/ef/0a1b2c3d-0000-0000-0000-000000000000";

function duration(value: string[] = []) {
  return {
    name: "duration", type: QUESTION, path: "details/duration", text: "Is this several days?",
    dataType: "text", minAnswers: 1, maxAnswers: 1, options: [], value,
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
    requirements: [ {
      name: "details", type: FORM_REQUIREMENT, label: "Request details", description: "When and why.",
      items: [ duration() ],
    } ],
    ...overrides,
  };
}

function json(body: unknown, init: { ok?: boolean; status?: number } = {}) {
  return Promise.resolve({
    ok: init.ok ?? true,
    status: init.status ?? 200,
    json: () => Promise.resolve(body),
  } as unknown as Response);
}

// Serves each read of the form in turn, so a test can say what the server reports after a save.
function serving(...reads: SubmissionForm[]) {
  let read = 0;
  return vi.fn((url: string, options?: { method?: string }) => {
    if (options?.method === "POST") {
      return json({});
    }
    const next = reads[Math.min(read, reads.length - 1)];
    read += 1;
    return json(next);
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
    // The point of the whole design: which questions apply depends on the answers, the server decides
    // it, and the way the editor finds out is by reading the form again. Nothing here evaluates a
    // condition — the return-date question simply appears in the next read.
    const withEndDate = form({
      requirements: [ {
        name: "details", type: FORM_REQUIREMENT, label: "Request details",
        items: [ duration([ "multiple days" ]), endDate() ],
      } ],
    });
    const fetchMock = serving(form(), withEndDate);
    vi.stubGlobal("fetch", fetchMock);

    render(<SubmissionEditor path={PATH} />);
    await userEvent.type(await screen.findByLabelText(/several days/), "multiple days");
    await userEvent.tab();

    expect(await screen.findByLabelText(/Which day are you back/)).toBeInTheDocument();
    const posted = fetchMock.mock.calls.find(([ , options ]) => (options as { method?: string })?.method === "POST");
    expect(posted?.[0]).toBe(PATH);
  });

  it("records a confirmation as an event on the submission", async () => {
    const withProvenance = form({ requirements: [ {
      name: "details", type: FORM_REQUIREMENT, label: "Request details", items: [ suggested() ],
    } ] });
    const fetchMock = serving(withProvenance);
    vi.stubGlobal("fetch", fetchMock);
    render(<SubmissionEditor path={PATH} />);
    await screen.findByText("AI found:");

    await userEvent.click(screen.getByRole("button", { name: "Looks right" }));

    const posted = fetchMock.mock.calls.find(([ , options ]) =>
      (options as { method?: string })?.method === "POST");
    expect(posted?.[0]).toBe(`${PATH}.reviewExtraction.json`);
    const body = (posted?.[1] as unknown as { body: URLSearchParams }).body;
    expect(body.get("question")).toBe("details/duration");
    expect(body.get("confirmed")).toBe("true");
  });

  // The answer is unchanged, so nothing about it should read as unsaved
  it("reports a refused review without claiming the answer failed to save", async () => {
    const withProvenance = form({ requirements: [ {
      name: "details", type: FORM_REQUIREMENT, label: "Request details", items: [ suggested() ],
    } ] });
    const fetchMock = vi.fn((url: string, options?: { method?: string }) => options?.method === "POST"
      ? json({ error: "Nothing was extracted for that" }, { ok: false, status: 400 })
      : json(withProvenance));
    vi.stubGlobal("fetch", fetchMock);
    render(<SubmissionEditor path={PATH} />);
    await screen.findByText("AI found:");

    await userEvent.click(screen.getByRole("button", { name: "Looks right" }));

    expect(await screen.findByLabelText("Not saved")).toBeInTheDocument();
  });

  it("reports a refused save on the field it belongs to", async () => {
    // A save can be refused — somebody submitted the request in another tab — and the field is where
    // that has to show, since the rest of the form is untouched
    const fetchMock = vi.fn((url: string, options?: { method?: string }) => options?.method === "POST"
      ? json({ error: "This request has been submitted" }, { ok: false, status: 403 })
      : json(form()));
    vi.stubGlobal("fetch", fetchMock);

    render(<SubmissionEditor path={PATH} />);
    await userEvent.type(await screen.findByLabelText(/several days/), "half day");
    await userEvent.tab();

    expect(await screen.findByLabelText("Not saved")).toBeInTheDocument();
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
    // The same two rules the save workflow enforces, answered by the server, so the editor offers
    // editing only where a save would be accepted rather than finding out from a refusal
    vi.stubGlobal("fetch", serving(form({ editable: false })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText(/can no longer be changed/)).toBeInTheDocument();
    expect(screen.getByLabelText(/several days/)).toBeDisabled();
  });

  it("shows a requirement that holds no questions, rather than dropping it", async () => {
    // An approval is still something the request is waiting on, and leaving it out would say the
    // request asks less than it does
    vi.stubGlobal("fetch", serving(form({
      requirements: [ approval({}) ],
    })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText("Approval")).toBeInTheDocument();
  });

  describe("an approval", () => {
    it("says who it is waiting on while nobody has decided", async () => {
      vi.stubGlobal("fetch", serving(form({
        requirements: [ approval({ approverGroup: "time-off-approvers", approved: false }) ],
      })));

      render(<SubmissionEditor path={PATH} />);

      expect(await screen.findByText("Waiting for approval from time-off-approvers")).toBeInTheDocument();
    });

    it("says only that it is waiting when nobody is named", async () => {
      // An approval nobody has been assigned still waits, and saying so beats saying nothing
      vi.stubGlobal("fetch", serving(form({
        requirements: [ approval({ approverGroup: "", approved: false }) ],
      })));

      render(<SubmissionEditor path={PATH} />);

      expect(await screen.findByText("Waiting for approval")).toBeInTheDocument();
    });

    it("reports the decision once somebody has made it", async () => {
      vi.stubGlobal("fetch", serving(form({
        requirements: [ approval({
          approved: true, decidedBy: "priya", decidedAt: "2026-08-27T09:15:30.500-05:00",
        }) ],
      })));

      render(<SubmissionEditor path={PATH} />);

      expect(await screen.findByText(/Approved by priya on /)).toBeInTheDocument();
    });

    it("reports a refusal as a decision rather than as silence", async () => {
      // Without this a refused request looks like one nobody has looked at yet
      vi.stubGlobal("fetch", serving(form({
        requirements: [ approval({ approved: false, decidedBy: "priya" }) ],
      })));

      render(<SubmissionEditor path={PATH} />);

      expect(await screen.findByText("Reviewed by priya, and not approved")).toBeInTheDocument();
    });
  });

  // One approval requirement, with whatever the projection is saying about it
  function approval(state: Record<string, unknown>) {
    return { name: "approval", type: "sch/ApprovalRequirement", label: "Approval", ...state };
  }

  describe("answering a document requirement", () => {
    const NOTE = {
      name: "doctorsNote",
      type: DOCUMENT_REQUIREMENT,
      label: "Doctor's note",
      description: "A note covering the days you were unwell.",
      required: true,
      acceptedFileTypes: [ ".doc", "application/pdf" ],
      template: "/Schemas/timeOffRequest/v1/doctorsNote/template",
      attached: [] as string[],
    };

    function asked(note: Partial<typeof NOTE> = {}, overrides: Partial<SubmissionForm> = {}) {
      return form({ requirements: [ { ...NOTE, ...note } ], ...overrides });
    }

    // A .doc, because the browser-side check now looks inside the file and a legacy Word document is
    // recognised by eight bytes rather than by a library these tests would have to stand in for.
    const OLE_MAGIC = new Uint8Array([ 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1 ]);

    function pick(name = "note.doc", type = "application/msword") {
      return new File([ OLE_MAGIC ], name, { type });
    }

    it("offers to attach a file, with the types it takes and the blank to start from", async () => {
      vi.stubGlobal("fetch", serving(asked()));

      render(<SubmissionEditor path={PATH} />);

      const input = await screen.findByLabelText(/Attach a file for "Doctor's note"/);
      expect(input).toHaveAttribute("accept", ".doc,application/pdf");
      expect(screen.getByText("Nothing attached yet")).toBeInTheDocument();
      expect(screen.getByRole("link", { name: "Download the blank form" }))
        .toHaveAttribute("href", "/Schemas/timeOffRequest/v1/doctorsNote/template");
    });

    it("says nothing about types or blanks where the requirement offers none", async () => {
      vi.stubGlobal("fetch", serving(asked({
        acceptedFileTypes: undefined, template: undefined, attached: undefined,
      })));

      render(<SubmissionEditor path={PATH} />);

      expect(await screen.findByLabelText(/Attach a file/)).not.toHaveAttribute("accept");
      expect(screen.queryByRole("link", { name: "Download the blank form" })).toBeNull();
      expect(screen.getByText("Nothing attached yet")).toBeInTheDocument();
    });

    it("says skipping it is allowed when the form says so", async () => {
      vi.stubGlobal("fetch", serving(asked({ required: false })));

      render(<SubmissionEditor path={PATH} />);

      expect(await screen.findByText("Nothing attached yet — optional")).toBeInTheDocument();
    });

    it("names what is already there, so a form reopened later does not look untouched", async () => {
      vi.stubGlobal("fetch", serving(asked({ attached: [ "note.doc" ] })));

      render(<SubmissionEditor path={PATH} />);

      expect(await screen.findByText("Attached: note.doc")).toBeInTheDocument();
      expect(screen.queryByText("Nothing attached yet")).toBeNull();
    });

    it("posts the file as an event on the submission, then reads the form again", async () => {
      const fetchMock = serving(asked(), asked({ attached: [ "note.doc" ] }));
      vi.stubGlobal("fetch", fetchMock);

      render(<SubmissionEditor path={PATH} />);
      await userEvent.upload(await screen.findByLabelText(/Attach a file/), pick());

      // The selector names the event: a bare POST to a submission means `save`
      // `.json` after the selector, because Sling would otherwise read `attachDocument` as the
      // extension and the POST would mean `save`
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
      expect(await screen.findByText("Attached: note.doc")).toBeInTheDocument();
    });

    it("says why the engine refused a file, in the engine's own words", async () => {
      // A file the browser-side check is happy with, so the request really does reach the server.
      // What the server then refuses, it refuses on its own reading of the request, and that reason
      // is the one worth showing.
      vi.stubGlobal("fetch", vi.fn((url: string, options?: { method?: string }) =>
        options?.method === "POST"
          ? json({ error: "This request no longer takes documents" }, { ok: false, status: 400 })
          : json(asked({ acceptedFileTypes: undefined }))));

      render(<SubmissionEditor path={PATH} />);
      await userEvent.upload(await screen.findByLabelText(/Attach a file/), pick());

      expect(await screen.findByText("This request no longer takes documents")).toBeInTheDocument();
    });

    // The browser-side check, which is there so a person finds out at once rather than after a slow
    // upload. The server checks again; this only saves the wait.
    it("refuses a file it can see is wrong before sending it", async () => {
      const fetchMock = serving(asked({ acceptedFileTypes: undefined }));
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
            status: 500,
            json: () => Promise.reject(new Error("not json")),
          } as unknown as Response)
          : json(asked())));

      render(<SubmissionEditor path={PATH} />);
      await userEvent.upload(await screen.findByLabelText(/Attach a file/), pick());

      expect(await screen.findByText(/This file could not be attached \(500\)/)).toBeInTheDocument();
    });

    it("stringifies a refusal that is not an error at all", async () => {
      // Nothing promises an Error, and a page showing "[object Object]" is how that usually surfaces
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
      await userEvent.click(screen.getByRole("button", { name: /Close/ }));

      // The input is cleared after each pick, so the same file counts as a change and can be retried
      await userEvent.upload(input, pick());

      await waitFor(() => expect(screen.queryByText("The server was busy")).toBeNull());
    });

    it("does nothing when the file dialog was dismissed without a choice", async () => {
      const fetchMock = serving(asked());
      vi.stubGlobal("fetch", fetchMock);

      render(<SubmissionEditor path={PATH} />);
      // What a cancelled dialog looks like to the change handler, which `userEvent.upload` cannot
      // express: it always has a file to give
      fireEvent.change(await screen.findByLabelText(/Attach a file/), { target: { files: [] } });

      expect(fetchMock.mock.calls.some(call => call[0].includes(".attachDocument"))).toBe(false);
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
      requirements: [ {
        name: "details", type: FORM_REQUIREMENT, label: "Request details",
        items: [ { name: "when", type: SECTION, label: "Dates", description: "When you are away",
          items: [ endDate() ] } ],
      } ],
    })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText("Dates")).toBeInTheDocument();
    expect(screen.getByText("When you are away")).toBeInTheDocument();
    expect(screen.getByLabelText(/Which day are you back/)).toBeInTheDocument();
  });

  it("falls back on the name when a requirement or a section is unlabelled", async () => {
    // The projection always carries a label and empties it rather than omitting it
    // (`Objects.toString(getLabel(), "")`), so this is what an unlabelled block actually arrives as —
    // and it still has to be identifiable rather than headed by nothing
    vi.stubGlobal("fetch", serving(form({
      requirements: [ {
        name: "details", type: FORM_REQUIREMENT, label: "",
        items: [ { name: "when", type: SECTION, label: "", items: [ endDate() ] } ],
      } ],
    })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText("details")).toBeInTheDocument();
    expect(screen.getByText("when")).toBeInTheDocument();
  });

  it("reports a save that failed without an Error to explain it", async () => {
    // A rejection is not necessarily an Error — a thrown string reaches the same handler — and the
    // field still has to say what happened rather than "undefined"
    const fetchMock = vi.fn((url: string, options?: { method?: string }) => options?.method === "POST"
      ? Promise.reject("the request went nowhere")
      : json(form()));
    vi.stubGlobal("fetch", fetchMock);

    render(<SubmissionEditor path={PATH} />);
    await userEvent.type(await screen.findByLabelText(/several days/), "half day");
    await userEvent.tab();

    const failure = await screen.findByLabelText("Not saved");
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

    expect(await screen.findByText(/could not be loaded \(404\)/)).toBeInTheDocument();
  });

  it("keeps the newest answer when two are finished in quick succession", async () => {
    // Saves are sent in the order they were given, but their reads can land out of order, and an
    // older form would put back what the newer one replaced
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
});
