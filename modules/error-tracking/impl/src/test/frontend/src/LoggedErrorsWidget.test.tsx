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

import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor } from "@testing-library/react";

import LoggedErrorsWidget from "@iap/error-tracking/LoggedErrorsWidget";
import { appTheme } from "@iap/frontend-commons/appTheme";

const page = (totalrows: number, approximate: boolean) => ({
  rows: [], offset: 0, limit: 1, returnedrows: 0, totalrows, totalIsApproximate: approximate,
});

// A body can be read only once, and the widget asks two questions in parallel, so each call has to
// be answered with its OWN Response. A single mockResolvedValue would fail the second read.
const answering = (needing: number, total: number, approximate = false) =>
  vi.fn((url: string) => Promise.resolve(new Response(
    JSON.stringify(url.includes("fieldValue=unacknowledged") ? page(needing, approximate) : page(total, approximate)),
    { status: 200, headers: { "Content-Type": "application/json" } })));

// The widget renders only the summary; the way through to the list is the dashboard frame's own
// header action, declared on the extension rather than drawn here.
const widget = () => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <LoggedErrorsWidget />
  </ThemeProvider>
);

describe("LoggedErrorsWidget", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("says how much needs attention and how much there is altogether", async () => {
    vi.stubGlobal("fetch", answering(3, 41));
    widget();

    expect(await screen.findByText("3")).toBeInTheDocument();
    expect(screen.getByText("41")).toBeInTheDocument();
    expect(screen.getByText("Needing attention")).toBeInTheDocument();
    expect(screen.getByText("Recorded in total")).toBeInTheDocument();
  });

  it("counts through the errors' own homepage, filtering on the derived marker", async () => {
    const fetchMock = answering(0, 0);
    vi.stubGlobal("fetch", fetchMock);
    widget();

    await waitFor(() => { expect(fetchMock).toHaveBeenCalledTimes(2); });
    const urls = fetchMock.mock.calls.map(call => call[0]);
    expect(urls.every(url => url.startsWith("/LoggedErrors.paginate.json?"))).toBe(true);
    expect(urls.some(url => url.includes("fieldName=computedTags&fieldComparator=%3D&fieldValue=unacknowledged")))
      .toBe(true);
  });

  it("marks the counts as lower bounds when the server stopped counting", async () => {
    vi.stubGlobal("fetch", answering(1, 10000, true));
    widget();

    expect(await screen.findByText("10000+")).toBeInTheDocument();
  });

  it("says nothing has been recorded rather than leaving two bare zeroes", async () => {
    vi.stubGlobal("fetch", answering(0, 0));
    widget();

    expect(await screen.findByText("Nothing has been recorded yet.")).toBeInTheDocument();
  });

  it("says everything has been dealt with when there is something but none of it is outstanding", async () => {
    vi.stubGlobal("fetch", answering(0, 12));
    widget();

    expect(await screen.findByText("Everything recorded has been dealt with.")).toBeInTheDocument();
  });

  it("does not claim everything is dealt with while something needs attention", async () => {
    vi.stubGlobal("fetch", answering(2, 12));
    widget();

    await screen.findByText("2");
    expect(screen.queryByText("Everything recorded has been dealt with.")).not.toBeInTheDocument();
  });

  it("says the errors are unavailable rather than showing zeroes", async () => {
    // Reaching the administration console is not the same as being allowed to read /LoggedErrors,
    // and two zeroes would be the opposite claim: that nothing has ever gone wrong
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response("", { status: 404 }))));
    widget();

    expect(await screen.findByText("The recorded errors are not available to you."))
      .toBeInTheDocument();
    expect(screen.queryByText("Recorded in total")).not.toBeInTheDocument();
  });

  it("shows a placeholder until the counts arrive", () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => { /* never settles */ })));
    widget();

    expect(screen.getByLabelText("Loading the error summary")).toBeInTheDocument();
  });

  it("does nothing with counts that arrive after it has gone", async () => {
    // The dashboard re-lays itself out when the persona changes, so a widget can leave while its
    // own requests are still in flight. Both of them have to be answered: the counts come from a
    // Promise.all, so resolving one leaves the chain pending and the guards below unexercised.
    const answers: ((response: Response) => void)[] = [];
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(resolve => { answers.push(resolve); })));
    const { unmount } = widget();
    await waitFor(() => { expect(answers).toHaveLength(2); });

    unmount();
    await act(async () => {
      answers.forEach(answer => {
        answer(new Response(JSON.stringify(page(1, false)),
          { status: 200, headers: { "Content-Type": "application/json" } }));
      });
      // One microtask is not enough: the counts are read through two awaited json() calls behind a
      // Promise.all, so the chain needs a macrotask to run out
      await new Promise(resolve => { setTimeout(resolve, 0); });
    });

    expect(screen.queryByText("Recorded in total")).not.toBeInTheDocument();
  });
});
