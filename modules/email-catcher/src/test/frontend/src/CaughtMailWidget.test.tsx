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
import { MemoryRouter } from "react-router";

import CaughtMailWidget from "@iap/email-catcher/CaughtMailWidget";

// A fresh answer per request: the list and the switch each read the summary.
const summary = (enabled: boolean, total: number) => () => Promise.resolve(new Response(
  JSON.stringify({ enabled: { label: "Catching mail", value: enabled }, total: { label: "Caught so far", value: total } }),
  { status: 200, headers: { "Content-Type": "application/json" } }
));

// Loading, refusal and rendering are tested in WidgetStatList.test.tsx.
describe("CaughtMailWidget", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("lists the figures the summary reports", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(
      JSON.stringify({ enabled: { label: "Catching mail", value: true }, total: { label: "Caught so far", value: 2 } }),
      { status: 200, headers: { "Content-Type": "application/json" } }
    ));
    render(<MemoryRouter><CaughtMailWidget /></MemoryRouter>);

    await waitFor(() => { expect(fetchMock).toHaveBeenCalled(); });
    expect(fetchMock.mock.calls[0][0]).toBe("/CaughtMail.adminSummary.json");
    expect(await screen.findByText("On")).toBeInTheDocument();
  });

  it("says so when the summary is not available to this reader", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("", { status: 403 }));
    render(<MemoryRouter><CaughtMailWidget /></MemoryRouter>);

    expect(await screen.findByText("The caught mail summary is not available to you.")).toBeInTheDocument();
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
  });

  it("offers the switch, set to what is in force", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(summary(true, 3));
    render(<MemoryRouter><CaughtMailWidget /></MemoryRouter>);

    expect(await screen.findByRole("switch", { name: "Catch mail instead of sending it" })).toBeChecked();
  });

  it("reads the summary again once the switch has written the setting", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(summary(false, 0));
    render(<MemoryRouter><CaughtMailWidget /></MemoryRouter>);
    await userEvent.click(await screen.findByRole("switch", { name: "Catch mail instead of sending it" }));
    const before = fetchMock.mock.calls.filter(([ url ]) => url === "/CaughtMail.adminSummary.json").length;

    await userEvent.click(screen.getByRole("button", { name: "Catch mail" }));

    await waitFor(() => {
      expect(fetchMock.mock.calls.filter(([ url ]) => url === "/CaughtMail.adminSummary.json").length)
        .toBe(before + 2);
    });
  });
});
