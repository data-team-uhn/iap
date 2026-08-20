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
import { FORM_REQUIREMENT, QUESTION, SECTION, type SubmissionForm } from "@iap/submissions/submissionForm";

const PATH = "/Submissions/ab/cd/ef/0a1b2c3d-0000-0000-0000-000000000000";

function duration(value: string[] = []) {
  return {
    name: "duration", type: QUESTION, path: "details/duration", text: "Is this several days?",
    dataType: "text", required: true, multiple: false, options: [], value,
  };
}

function endDate() {
  return {
    name: "endDate", type: QUESTION, path: "details/endDate", text: "Which day are you back?",
    dataType: "date", required: true, multiple: false, options: [], value: [] as string[],
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

  it("cannot be answered once the request is no longer the submitter's to change", async () => {
    // The same two rules the save workflow enforces, answered by the server, so the editor offers
    // editing only where a save would be accepted rather than finding out from a refusal
    vi.stubGlobal("fetch", serving(form({ editable: false })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText(/can no longer be changed/)).toBeInTheDocument();
    expect(screen.getByLabelText(/several days/)).toBeDisabled();
  });

  it("shows a requirement that holds no questions, rather than dropping it", async () => {
    // A document or an approval is still something the submitter has to do
    vi.stubGlobal("fetch", serving(form({
      requirements: [ { name: "doctorsNote", type: "sch/DocumentRequirement", label: "Doctor's note" } ],
    })));

    render(<SubmissionEditor path={PATH} />);

    expect(await screen.findByText("Doctor's note")).toBeInTheDocument();
    expect(screen.getByText(/cannot be completed here yet/)).toBeInTheDocument();
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
