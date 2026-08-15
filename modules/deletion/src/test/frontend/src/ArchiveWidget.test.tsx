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
import { MemoryRouter } from "react-router";

import ArchiveWidget from "@iap/deletion/ArchiveWidget";
import { appTheme } from "@iap/frontend-commons/appTheme";

const jsonResponse = (status: number, body: unknown) => new Response(JSON.stringify(body), {
  status,
  headers: { "Content-Type": "application/json" },
});

// The widget lives on the dashboard, inside the application shell's router, and links to the
// archive view through it rather than by reloading the page.
const widget = () => render(
  <MemoryRouter>
    <ThemeProvider theme={appTheme} defaultMode="light">
      <ArchiveWidget />
    </ThemeProvider>
  </MemoryRouter>
);

describe("ArchiveWidget", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("shows how much has been archived over each period", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(200, { last24Hours: 3, lastWeek: 12, total: 218, approximate: false }));
    widget();

    expect(await screen.findByText("3")).toBeInTheDocument();
    expect(screen.getByText("12")).toBeInTheDocument();
    expect(screen.getByText("218")).toBeInTheDocument();
  });

  it("reads the counts from the archive's summary endpoint", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(200, { last24Hours: 0, lastWeek: 0, total: 0, approximate: false }));
    widget();

    await waitFor(() => { expect(fetchMock).toHaveBeenCalled(); });
    expect(fetchMock.mock.calls[0][0]).toBe("/Archive.summary.json");
  });

  it("links through to the archive view without reloading the page", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(200, { last24Hours: 0, lastWeek: 0, total: 0, approximate: false }));
    widget();

    const link = await screen.findByRole("link", { name: "Open the archive" });
    expect(link).toHaveAttribute("href", "/Archive");
  });

  it("marks the counts as lower bounds when the server stopped counting", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(200, { last24Hours: 1, lastWeek: 2, total: 10000, approximate: true }));
    widget();

    expect(await screen.findByText("10000+")).toBeInTheDocument();
  });

  it("says the archive is unavailable rather than showing zeroes", async () => {
    // Wearing the administrator persona is not the same as being allowed to read the archive, and
    // three zeroes would be a claim that nothing has ever been deleted
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("", { status: 404 }));
    widget();

    expect(await screen.findByText("The archive is not available to you.")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Open the archive" })).not.toBeInTheDocument();
  });

  it("shows a placeholder until the counts arrive", () => {
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(() => { /* never settles */ }));
    widget();

    expect(screen.getByLabelText("Loading the archive summary")).toBeInTheDocument();
  });

  it("does nothing with counts that arrive after it has gone", async () => {
    // The dashboard re-lays itself out when the persona changes, so a widget can leave while its
    // own request is still in flight
    let answer!: (response: Response) => void;
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(resolve => { answer = resolve; }));
    const { unmount } = widget();

    unmount();
    await act(async () => {
      answer(jsonResponse(200, { last24Hours: 1, lastWeek: 1, total: 1, approximate: false }));
      await Promise.resolve();
    });

    expect(screen.queryByText("Archived in total")).not.toBeInTheDocument();
  });

  it("does nothing with a failure that arrives after it has gone either", async () => {
    let fail!: (reason: Error) => void;
    vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise((_resolve, reject) => { fail = reject; }));
    const { unmount } = widget();

    unmount();
    await act(async () => {
      fail(new Error("too late"));
      await Promise.resolve();
    });

    expect(screen.queryByText("The archive is not available to you.")).not.toBeInTheDocument();
  });
});
