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

import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import ReadingBanner from "@iap/submissions/ReadingBanner";
import type { ExtractionState, SubmissionForm } from "@iap/submissions/submissionForm";

const PATH = "/Submissions/ab/cd/ef/0a1b2c3d-0000-0000-0000-000000000000";

function json(body: unknown, init: { ok?: boolean; status?: number } = {}) {
  return Promise.resolve({
    ok: init.ok ?? true,
    status: init.status ?? 200,
    url: "",
    json: () => Promise.resolve(body),
  } as unknown as Response);
}

function form(extraction?: ExtractionState, overrides: Partial<SubmissionForm> = {}): SubmissionForm {
  return { path: PATH, title: "A study", editable: true, readsDocuments: true, requirements: [], extraction,
    ...overrides };
}

// Serves the form, and answers each event with `refusal` when one is given
function serving(read: SubmissionForm, refusal?: string) {
  return vi.fn((url: string, options?: { method?: string }) => {
    if (options?.method === "POST") {
      return refusal ? json({ error: refusal }, { ok: false, status: 409 }) : json({});
    }
    return json(read);
  });
}

function posted(fetchMock: ReturnType<typeof serving>): unknown[] {
  return fetchMock.mock.calls
    .filter(([ , options ]) => options?.method === "POST")
    .map(([ url ]) => url);
}

describe("ReadingBanner", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("shows nothing for a request whose documents nothing reads", async () => {
    const fetchMock = serving(form({ status: "done" }, { readsDocuments: false }));
    vi.stubGlobal("fetch", fetchMock);

    const { container } = render(<ReadingBanner path={PATH} reloads={0} onChanged={vi.fn()} />);

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it("shows nothing before a reading was asked for", async () => {
    const fetchMock = serving(form());
    vi.stubGlobal("fetch", fetchMock);

    const { container } = render(<ReadingBanner path={PATH} reloads={0} onChanged={vi.fn()} />);

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it("follows a running reading and stops it", async () => {
    const fetchMock = serving(form({ status: "running", parsed: true, reading: true }));
    vi.stubGlobal("fetch", fetchMock);
    const changed = vi.fn();

    render(<ReadingBanner path={PATH} reloads={0} onChanged={changed} />);
    await userEvent.click(await screen.findByRole("button", { name: "Stop" }));

    expect(posted(fetchMock)).toEqual([ `${PATH}.stopProcessing.json` ]);
    await waitFor(() => expect(changed).toHaveBeenCalled());
  });

  it("offers no Stop on a request the reader may not change", async () => {
    vi.stubGlobal("fetch", serving(form({ status: "running" }, { editable: false })));

    render(<ReadingBanner path={PATH} reloads={0} onChanged={vi.fn()} />);

    expect(await screen.findByText("Parsing")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Stop" })).not.toBeInTheDocument();
  });

  it("says why a reading failed, and sends a failed parse back to be parsed", async () => {
    const detail = vi.spyOn(console, "info").mockImplementation(() => undefined);
    const fetchMock = serving(form({ status: "failed", message: "The document could not be read. Please try again",
      retryable: true, parsed: true, detail: "The daemon is down" }));
    vi.stubGlobal("fetch", fetchMock);

    render(<ReadingBanner path={PATH} reloads={0} onChanged={vi.fn()} />);
    await userEvent.click(await screen.findByRole("button", { name: "Try again" }));

    expect(screen.getAllByText("The document could not be read. Please try again").length).toBeGreaterThan(0);
    expect(posted(fetchMock)).toEqual([ `${PATH}.retryParse.json` ]);
    expect(detail).toHaveBeenCalledWith(expect.any(String), "The daemon is down");
    detail.mockRestore();
  });

  it("reads again once a reading is done", async () => {
    const fetchMock = serving(form({ status: "done", parsed: true }));
    vi.stubGlobal("fetch", fetchMock);

    render(<ReadingBanner path={PATH} reloads={0} onChanged={vi.fn()} />);
    expect(await screen.findByText(/Answers were read from your document/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Read again" }));

    expect(posted(fetchMock)).toEqual([ `${PATH}.readAgain.json` ]);
  });

  it("shows the reading's own message, and a default one for a failure that gave none", async () => {
    vi.stubGlobal("fetch", serving(form({ status: "done", message: "Some questions could not be read" })));
    const { unmount } = render(<ReadingBanner path={PATH} reloads={0} onChanged={vi.fn()} />);
    expect(await screen.findByText("Some questions could not be read")).toBeInTheDocument();
    unmount();

    vi.stubGlobal("fetch", serving(form({ status: "failed" }, { editable: false })));
    render(<ReadingBanner path={PATH} reloads={0} onChanged={vi.fn()} />);
    expect((await screen.findAllByText(/could not be read/)).length).toBeGreaterThan(0);
    expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
  });

  it("warns when the reading stopped the request", async () => {
    vi.stubGlobal("fetch", serving(form({ status: "done", blocked: true,
      message: "This document is not a research proposal." })));

    render(<ReadingBanner path={PATH} reloads={0} onChanged={vi.fn()} />);

    expect(await screen.findByText("This document is not a research proposal.")).toBeInTheDocument();
  });

  it("says why an event was refused", async () => {
    vi.stubGlobal("fetch", serving(form({ status: "done" }), "This request is already being read"));

    render(<ReadingBanner path={PATH} reloads={0} onChanged={vi.fn()} />);
    await userEvent.click(await screen.findByRole("button", { name: "Read again" }));

    expect(await screen.findByText("This request is already being read")).toBeInTheDocument();
  });

  it("reads the form again when the page reads the request again", async () => {
    const fetchMock = serving(form({ status: "running" }));
    vi.stubGlobal("fetch", fetchMock);

    const { rerender } = render(<ReadingBanner path={PATH} reloads={0} onChanged={vi.fn()} />);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    rerender(<ReadingBanner path={PATH} reloads={1} onChanged={vi.fn()} />);

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
  });
});
