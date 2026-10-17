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
import { MemoryRouter } from "react-router";

import LoggedErrorsWidget from "@iap/error-tracking/LoggedErrorsWidget";

// Loading, refusal and rendering are tested in WidgetStatList.test.tsx.
describe("LoggedErrorsWidget", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("lists the figures the summary reports", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(
      JSON.stringify({ needingAttention: { label: "Needing attention", value: 3, important: "nonzero" }, total: { label: "Recorded in total", value: 41 } }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    ));
    render(<MemoryRouter><LoggedErrorsWidget /></MemoryRouter>);

    await waitFor(() => { expect(fetchMock).toHaveBeenCalled(); });
    expect(fetchMock.mock.calls[0][0]).toBe("/LoggedErrors.adminSummary.json");
    expect(await screen.findByText("3")).toBeInTheDocument();
  });

  it("says so when the summary is not available to this reader", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("", { status: 403 }));
    render(<MemoryRouter><LoggedErrorsWidget /></MemoryRouter>);

    expect(await screen.findByText("The recorded errors summary is not available to you.")).toBeInTheDocument();
  });
});
