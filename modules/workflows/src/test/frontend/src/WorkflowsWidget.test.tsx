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

import WorkflowsWidget from "@iap/workflows/WorkflowsWidget";

// Loading, refusal and rendering are tested in WidgetStatList.test.tsx.
describe("WorkflowsWidget", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("lists the figures the summary reports", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(
      JSON.stringify({ "/Workflows": { label: "Workflows", value: 12, path: "/Workflows" } }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    ));
    render(<MemoryRouter><WorkflowsWidget /></MemoryRouter>);

    await waitFor(() => { expect(fetchMock).toHaveBeenCalled(); });
    expect(fetchMock.mock.calls[0][0]).toBe("/Workflows.adminSummary.json");
    expect(await screen.findByRole("link", { name: "Workflows" }))
      .toHaveAttribute("href", "/admin/workflows/Workflows");
  });

  it("says so when the summary is not available to this reader", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("", { status: 403 }));
    render(<MemoryRouter><WorkflowsWidget /></MemoryRouter>);

    expect(await screen.findByText("The workflows summary is not available to you.")).toBeInTheDocument();
  });
});
