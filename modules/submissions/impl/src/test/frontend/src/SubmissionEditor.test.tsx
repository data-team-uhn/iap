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

import { validateUpload } from "@iap/frontend-commons/fileValidation";
import { fetchUploadLimits } from "@iap/frontend-commons/uploadLimits";
import { loadAnswerComponents } from "@iap/submissions/answers";
// Imported rather than fetched. In a browser each answer component arrives as its own asset, named
// by an extension on the AnswerComponent point, and registers itself as it is evaluated.
import "@iap/submissions/answers/BooleanAnswer";
import "@iap/submissions/answers/ChoiceAnswer";
import "@iap/submissions/answers/DateAnswer";
import "@iap/submissions/answers/NumberAnswer";
import "@iap/submissions/answers/TextAnswer";
import {
  clearRequirementComponents,
  registerRequirementComponent,
  type RequirementComponent,
  type RequirementComponentCandidate,
} from "@iap/submissions/requirementComponents";
import { loadRequirementComponents, registerBuiltinRequirementComponents } from "@iap/submissions/requirements";
import SubmissionEditor from "@iap/submissions/SubmissionEditor";
import {
  DOCUMENT_REQUIREMENT_TYPE,
  type DocumentRequirement,
  FORM_REQUIREMENT_TYPE,
  type FormItem,
  type FormRequirement,
  QUESTION,
  SECTION,
  type SubmissionForm,
} from "@iap/submissions/submissionForm";

import { NEEDS_APPROVAL, approval } from "./approvals.fixture";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(() => Promise.resolve([])),
}));

// The file check has its own tests; here it passes every file unless a test says otherwise
vi.mock("@iap/frontend-commons/fileValidation", () => ({
  validateUpload: vi.fn(() => Promise.resolve(undefined)),
}));

// The server gives no limits unless a test says otherwise, so the check's own defaults apply
vi.mock("@iap/frontend-commons/uploadLimits", () => ({
  fetchUploadLimits: vi.fn(() => Promise.resolve({})),
}));

// Settles the loads before the first render, so the editor draws the form rather than a spinner,
// and a field draws its input
beforeEach(async () => {
  await loadRequirementComponents();
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
    name: "details", path: "/Schemas/timeOffRequest/v1/details", type: FORM_REQUIREMENT_TYPE, label: "Request details",
    items, ...overrides
  };
}

function endDate() {
  return {
    name: "endDate", type: QUESTION, path: "details/endDate", text: "Which day are you back?",
    dataType: "date", minAnswers: 1, maxAnswers: 1, options: [], value: [] as string[],
  };
}

function form(overrides: Partial<SubmissionForm> = {}): SubmissionForm {
  return {
    path: PATH,
    title: "A long weekend",
    editable: true,
    requirements: [ details([ duration() ], { description: "When and why." }) ],
    ...overrides,
  };
}

function json(body: unknown, init: { ok?: boolean; status?: number } = {}) {
  return Promise.resolve({
    ok: init.ok ?? true,
    status: init.status ?? 200,
    // The authenticated fetch reads this to tell a form from a login page served with a 200
    url: "",
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

// Refuses every save, as the server does once the request is no longer a draft: the request is
// not in a state that admits the save, which is a conflict rather than a refusal of the user.
function refusingSaves() {
  return vi.fn((url: string, options?: { method?: string }) => options?.method === "POST"
    ? json({ error: "This request has been submitted and can no longer be changed" }, { ok: false, status: 409 })
    : json(form()));
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
    // The return-date question arrives with the next read of the form
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

  it("tells the page once an answer is saved", async () => {
    const changed = vi.fn();
    vi.stubGlobal("fetch", serving(form()));

    render(<SubmissionEditor path={PATH} onChanged={changed} />);
    await userEvent.type(await screen.findByLabelText(/several days/), "multiple days");
    await userEvent.tab();

    await waitFor(() => expect(changed).toHaveBeenCalledTimes(1));
  });

  it("reports a refused save on the field it belongs to", async () => {
    vi.stubGlobal("fetch", refusingSaves());

    render(<SubmissionEditor path={PATH} />);
    await userEvent.type(await screen.findByLabelText(/several days/), "half day");
    await userEvent.tab();

    expect(await screen.findByText("Not saved")).toBeInTheDocument();
  });

  it("tells the page nothing when a save is refused", async () => {
    const changed = vi.fn();
    vi.stubGlobal("fetch", refusingSaves());

    render(<SubmissionEditor path={PATH} onChanged={changed} />);
    await userEvent.type(await screen.findByLabelText(/several days/), "half day");
    await userEvent.tab();

    expect(await screen.findByText("Not saved")).toBeInTheDocument();
    expect(changed).not.toHaveBeenCalled();
  });

  it("cannot be answered once the request is no longer the submitter's to change", async () => {
    vi.stubGlobal("fetch", serving(form({ editable: false })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText(/can no longer be changed/)).toBeInTheDocument();
    expect(screen.getByLabelText(/several days/)).toBeDisabled();
  });

  it("shows a requirement it cannot complete, rather than dropping it", async () => {
    vi.stubGlobal("fetch", serving(form({
      requirements: [ {
        name: "ethics", path: "/Schemas/timeOffRequest/v1/ethics", type: "sch/EthicsRequirement", label: "Ethics"
      } ],
    })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText("Ethics")).toBeInTheDocument();
    expect(screen.getByText(/cannot be completed here yet/)).toBeInTheDocument();
  });

  describe("drawing a kind of requirement another module registers", () => {
    const A_SAVE = Promise.resolve();

    // Shows the submission path it was handed, and reports a save and a change when pressed
    const Thing: RequirementComponent = ({ path, onSaving, onChanged }) => (
      <button type="button" onClick={() => {
        onSaving(A_SAVE);
        onChanged();
      }}>{path}</button>
    );

    const Broken: RequirementComponent = () => {
      throw new Error("this kind of requirement is broken");
    };

    const candidateFor = (type: string, component: RequirementComponent): RequirementComponentCandidate =>
      requirement => (requirement.type === type ? [ component, 50 ] : null);

    function contributed(type: string) {
      return { name: type, path: `/Schemas/timeOffRequest/v1/${type}`, type, label: `A ${type}` };
    }

    afterEach(() => {
      clearRequirementComponents();
      registerBuiltinRequirementComponents();
      vi.restoreAllMocks();
    });

    it("draws it with the registered component, handing it the submission and the editor's callbacks", async () => {
      registerRequirementComponent(candidateFor("test/Thing", Thing));
      const saving = vi.fn();
      const changed = vi.fn();
      const fetchMock = serving(form({ requirements: [ contributed("test/Thing") ] }));
      vi.stubGlobal("fetch", fetchMock);

      render(<SubmissionEditor path={PATH} onSaving={saving} onChanged={changed} />);
      await userEvent.click(await screen.findByRole("button", { name: PATH }));

      expect(saving).toHaveBeenCalledWith(A_SAVE);
      expect(changed).toHaveBeenCalledTimes(1);
      // The form is read again
      await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    });

    it("shows a component that fails to draw as a part it cannot complete, and draws the rest", async () => {
      vi.spyOn(console, "error").mockImplementation(() => undefined);
      registerRequirementComponent(candidateFor("test/Broken", Broken));
      vi.stubGlobal("fetch", serving(form({ requirements: [ contributed("test/Broken"), details([ duration() ]) ] })));

      render(<SubmissionEditor path={PATH} />);

      expect(await screen.findByText("A test/Broken")).toBeInTheDocument();
      expect(screen.getByText(/cannot be completed here yet/)).toBeInTheDocument();
      expect(screen.getByLabelText(/several days/)).toBeInTheDocument();
    });
  });

  it("says who an approval needs, since somebody else grants it", async () => {
    vi.stubGlobal("fetch", serving(form({ requirements: [ approval() ] })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText("Approval")).toBeInTheDocument();
    expect(screen.getByText(NEEDS_APPROVAL)).toBeInTheDocument();
  });

  describe("answering a document requirement", () => {
    const NOTE: DocumentRequirement = {
      name: "doctorsNote",
      path: "/Schemas/timeOffRequest/v1/doctorsNote",
      type: DOCUMENT_REQUIREMENT_TYPE,
      label: "Doctor's note",
      description: "A note covering the days you were unwell.",
      required: true,
      acceptedFileTypes: [ "application/pdf", "image/png" ],
      template: "/Schemas/timeOffRequest/v1/doctorsNote/template",
      templateName: "Doctor's note.docx",
      attached: [],
    };

    const NOTE_FILE = { title: "note.pdf", path: "/Submissions/x/d1/v1/file/uploadedFile" };

    function asked(note: Partial<DocumentRequirement> = {}, overrides: Partial<SubmissionForm> = {}) {
      return form({ requirements: [ { ...NOTE, ...note } ], ...overrides });
    }

    function pick(name = "note.pdf", type = "application/pdf") {
      return new File([ "%PDF" ], name, { type });
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

    it("offers a new version, not removal, while a file is attached", async () => {
      vi.stubGlobal("fetch", serving(asked({ attached: [ NOTE_FILE ] })));

      render(<SubmissionEditor path={PATH} />);

      const input = await screen.findByLabelText(/Upload a new version for "Doctor's note"/);
      expect(input).toHaveAttribute("accept", "application/pdf,image/png");
      expect(screen.queryByRole("button", { name: "Remove" })).toBeNull();
      expect(screen.queryByLabelText(/Attach a file/)).toBeNull();
    });

    it("tells the page once a document is attached", async () => {
      const changed = vi.fn();
      vi.stubGlobal("fetch", serving(asked(), asked({ attached: [ NOTE_FILE ] })));

      render(<SubmissionEditor path={PATH} onChanged={changed} />);
      await userEvent.upload(await screen.findByLabelText(/Attach a file/), pick());

      await waitFor(() => expect(changed).toHaveBeenCalledTimes(1));
    });

    it("checks the file against the types the requirement takes before sending it", async () => {
      vi.mocked(validateUpload).mockResolvedValueOnce("note.txt is not a file of an accepted type: .pdf.");
      const fetchMock = serving(asked());
      vi.stubGlobal("fetch", fetchMock);

      render(<SubmissionEditor path={PATH} />);
      fireEvent.change(await screen.findByLabelText(/Attach a file/), { target: { files: [ pick("note.txt") ] } });

      expect(await screen.findByRole("alert")).toHaveTextContent("note.txt is not a file of an accepted type: .pdf.");
      expect(vi.mocked(validateUpload)).toHaveBeenCalledWith(
        expect.any(File), expect.objectContaining({ accepted: [ "application/pdf", "image/png" ] }));
      expect(fetchMock.mock.calls.some(call => call[0] === `${PATH}.attachDocument.json`)).toBe(false);
    });

    it("checks the file against the server's limits", async () => {
      vi.mocked(fetchUploadLimits).mockResolvedValueOnce({ maxFileSize: 1024 });
      vi.stubGlobal("fetch", serving(asked()));

      render(<SubmissionEditor path={PATH} />);
      await userEvent.upload(await screen.findByLabelText(/Attach a file/), pick());

      await waitFor(() => expect(vi.mocked(validateUpload)).toHaveBeenCalledWith(
        expect.any(File), expect.objectContaining({ limits: { maxFileSize: 1024 } })));
    });

    it("reads the limits within the check's own time, and leaves the check what remains", async () => {
      vi.stubGlobal("fetch", serving(asked()));

      render(<SubmissionEditor path={PATH} />);
      await userEvent.upload(await screen.findByLabelText(/Attach a file/), pick());

      await waitFor(() => expect(vi.mocked(validateUpload)).toHaveBeenCalled());
      expect(vi.mocked(fetchUploadLimits).mock.lastCall?.[1]).toBeInstanceOf(AbortSignal);
      const timeoutMs = vi.mocked(validateUpload).mock.lastCall?.[1]?.timeoutMs ?? 0;
      expect(timeoutMs).toBeGreaterThan(0);
      expect(timeoutMs).toBeLessThanOrEqual(30_000);
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
      expect((body.get("file") as File).name).toBe("note.pdf");
      expect(init.headers).toBeUndefined();
      // From the form read after the upload
      expect(await screen.findByRole("link", { name: "note.pdf" })).toBeInTheDocument();
    });

    it("posts a new version as the same event, then reads the form again", async () => {
      const newer = { title: "note-v2.pdf", path: "/Submissions/x/d1/v2/file/uploadedFile" };
      const fetchMock = serving(asked({ attached: [ NOTE_FILE ] }), asked({ attached: [ newer ] }));
      vi.stubGlobal("fetch", fetchMock);

      render(<SubmissionEditor path={PATH} />);
      await userEvent.upload(await screen.findByLabelText(/Upload a new version/), pick("note-v2.pdf"));

      const upload = fetchMock.mock.calls.find(call => call[0] === `${PATH}.attachDocument.json`);
      expect(upload).toBeDefined();
      const body = (upload![1] as RequestInit).body as FormData;
      expect(body.get("requirement")).toBe("doctorsNote");
      expect((body.get("file") as File).name).toBe("note-v2.pdf");
      // From the form read after the upload
      expect(await screen.findByRole("link", { name: "note-v2.pdf" })).toBeInTheDocument();
    });

    it("does not offer a new version for a request that can no longer be changed", async () => {
      vi.stubGlobal("fetch", serving(asked({ attached: [ NOTE_FILE ] }, { editable: false })));

      render(<SubmissionEditor path={PATH} />);

      expect(await screen.findByLabelText(/Upload a new version/)).toBeDisabled();
    });

    it("says why the engine refused a file, in the engine's own words", async () => {
      // The requirement takes any type, since `userEvent.upload` drops a file that `accept` leaves out,
      // as a real file dialog does
      vi.stubGlobal("fetch", vi.fn((url: string, options?: { method?: string }) =>
        options?.method === "POST"
          ? json({ error: "A image/gif is not accepted here" }, { ok: false, status: 400 })
          : json(asked({ acceptedFileTypes: [] }))));

      render(<SubmissionEditor path={PATH} />);
      await userEvent.upload(await screen.findByLabelText(/Attach a file/), pick("scan.gif", "image/gif"));

      expect(await screen.findByText("A image/gif is not accepted here")).toBeInTheDocument();
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
      // Cleared after each pick. The test environment fires the change whatever the value, so this is
      // asserted outright.
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
      // A dismissed dialog, which `userEvent.upload` cannot express, since it always gives a file
      fireEvent.change(await screen.findByLabelText(/Attach a file/), { target: { files: [] } });

      expect(fetchMock.mock.calls.some(call => call[0] === `${PATH}.attachDocument.json`)).toBe(false);
    });

    it("cannot be attached to once the request is no longer the submitter's to change", async () => {
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

  it("falls back on the name when a requirement or a section is unlabelled", async () => {
    // The server sends an unlabelled requirement or section with an empty label, not without one
    vi.stubGlobal("fetch", serving(form({
      requirements: [ details(
        [ { name: "when", type: SECTION, label: "", items: [ endDate() ] } ], { label: "" }) ],
    })));

    render(<SubmissionEditor path={PATH} />);

    // As a heading, so it starts with a capital
    expect(await screen.findByText("Details")).toBeInTheDocument();
    expect(screen.getByText("when")).toBeInTheDocument();
  });

  it("reports a save that failed without an Error to explain it", async () => {
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

    expect(await screen.findByText(/could not be loaded \(404\)/)).toBeInTheDocument();
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

    await waitFor(() => expect(screen.queryByText("Stale")).not.toBeInTheDocument());
    expect(screen.getByText("Newest")).toBeInTheDocument();
  });
});

describe("SubmissionEditor with a reading", () => {
  afterEach(() => vi.unstubAllGlobals());

  function read(): SubmissionForm {
    return form({
      requirements: [ details([ {
        ...duration(),
        provenance: {
          suggested: [ "multiple days" ], offered: [ "multiple days" ], confidence: 0.9,
          passages: [ { quote: "The leave runs over several days" } ], evidenceRejected: false, actedOn: false,
        },
      } ], { extracted: true }) ],
    });
  }

  it("says a form is filled in from the document, and records a note about its quotes", async () => {
    const fetchMock = serving(read());
    vi.stubGlobal("fetch", fetchMock);

    render(<SubmissionEditor path={PATH} />);
    expect(await screen.findByText(/Filled in from your uploaded document/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /The leave runs over/ }));
    await userEvent.click(screen.getByRole("button", { name: "This passage does not support the answer" }));

    await waitFor(() => expect(fetchMock.mock.calls.some(([ url ]) => url === `${PATH}.reviewExtraction.json`))
      .toBe(true));
  });

  it("says when a note about the quotes was refused", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string, options?: { method?: string }) => options?.method === "POST"
      ? json({ error: "Nothing was read for this question" }, { ok: false, status: 400 })
      : json(read())));

    render(<SubmissionEditor path={PATH} />);
    await userEvent.click(await screen.findByRole("button", { name: /The leave runs over/ }));
    await userEvent.click(screen.getByRole("button", { name: "This passage does not support the answer" }));

    expect(await screen.findByText("Not saved")).toBeInTheDocument();
  });
});

describe("SubmissionEditor in steps", () => {
  afterEach(() => vi.unstubAllGlobals());

  function proposal(attached: number): DocumentRequirement {
    return {
      name: "proposal", path: "/Schemas/study/v1/proposal", type: DOCUMENT_REQUIREMENT_TYPE,
      label: "research proposal", required: true, acceptedFileTypes: [],
      attached: Array.from({ length: attached }, () => ({ title: "protocol.pdf" })),
    };
  }

  function category(value: string[]): FormRequirement {
    return {
      name: "category", path: "/Schemas/study/v1/category", type: FORM_REQUIREMENT_TYPE, label: "Kind of study",
      confirmsCategory: true, items: [ {
        name: "kind", type: QUESTION, path: "category/kind", text: "What kind of study is this?",
        dataType: "text", minAnswers: 1, maxAnswers: 1, options: [], value,
      } ],
    };
  }

  function study(): FormRequirement {
    return details([ duration() ], { name: "study", label: "The study" });
  }

  function stepped(overrides: Partial<SubmissionForm>): SubmissionForm {
    return form({ readsDocuments: true, requirements: [ proposal(1), category([]), study() ], ...overrides });
  }

  function posts(fetchMock: ReturnType<typeof serving>): unknown[] {
    return fetchMock.mock.calls.filter(([ , options ]) => options?.method === "POST").map(([ url ]) => url);
  }

  it("asks for each missing document before going on", async () => {
    vi.stubGlobal("fetch", serving(stepped({ requirements: [ proposal(0), category([]), study() ] })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText("Research proposal")).toBeInTheDocument();
    expect(screen.queryByText("Kind of study")).not.toBeInTheDocument();
    expect(screen.getByText("Please upload the research proposal.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Back" })).not.toBeInTheDocument();
  });

  it("sends the documents to be read, then asks to confirm the category", async () => {
    const fetchMock = serving(stepped({}));
    vi.stubGlobal("fetch", fetchMock);
    const onChanged = vi.fn();

    render(<SubmissionEditor path={PATH} onChanged={onChanged} />);
    await userEvent.click(await screen.findByRole("button", { name: "Next" }));

    expect(await screen.findByText("Kind of study")).toBeInTheDocument();
    expect(posts(fetchMock)).toEqual([ `${PATH}.retryParse.json` ]);
    expect(onChanged).toHaveBeenCalled();
  });

  it("reads the rest once the category is confirmed, and goes back", async () => {
    const fetchMock = serving(stepped({ extraction: { status: "done" },
      requirements: [ proposal(1), category([ "/Categories/x" ]), study() ] }));
    vi.stubGlobal("fetch", fetchMock);

    render(<SubmissionEditor path={PATH} />);
    await userEvent.click(await screen.findByRole("button", { name: "Next" }));

    expect(await screen.findByText("The study")).toBeInTheDocument();
    expect(posts(fetchMock)).toEqual([ `${PATH}.runMainExtraction.json` ]);
    expect(screen.queryByRole("button", { name: "Next" })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Back" }));
    expect(await screen.findByText("Kind of study")).toBeInTheDocument();
  });

  it("waits for the category before reading the rest", async () => {
    vi.stubGlobal("fetch", serving(stepped({ extraction: { status: "done" } })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText("Confirm the category to continue.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
  });

  it("locks the uploads once they were sent", async () => {
    vi.stubGlobal("fetch", serving(stepped({ extraction: { status: "done" } })));

    render(<SubmissionEditor path={PATH} />);
    await userEvent.click(await screen.findByRole("button", { name: "Back" }));

    expect(await screen.findByLabelText(/Upload a new version for "research proposal"/)).toBeDisabled();
  });

  it("says why going on was refused", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string, options?: { method?: string }) => options?.method === "POST"
      ? json({ error: "The documents could not be sent" }, { ok: false, status: 409 })
      : json(stepped({}))));

    render(<SubmissionEditor path={PATH} />);
    await userEvent.click(await screen.findByRole("button", { name: "Next" }));

    expect(await screen.findByText("The documents could not be sent")).toBeInTheDocument();
    expect(screen.getByText("Research proposal")).toBeInTheDocument();
  });

  it("shows a request that can no longer be changed on one page", async () => {
    vi.stubGlobal("fetch", serving(stepped({ editable: false })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText("The study")).toBeInTheDocument();
    expect(screen.getByText("Kind of study")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Next" })).not.toBeInTheDocument();
  });
});
