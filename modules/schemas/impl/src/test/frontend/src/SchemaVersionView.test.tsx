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
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";

import { appTheme } from "@iap/frontend-commons/appTheme";
import { NoticeProvider } from "@iap/frontend-commons/components/NoticeSnackbar";
import { getPageCrumbs } from "@iap/frontend-commons/pageCrumbs";
import SchemaPage from "@iap/schemas/SchemaPage";
import { clearTagDefinitionsCache } from "@iap/tags/tagDefinitions";

import { serveSchemas } from "./schemaServer.fixture";

vi.mock("@iap/frontend-commons/actionsManager", () => ({
  getActions: (point: string) => import("./actions.fixture").then(fixture => fixture.actionsFor(point)),
}));

afterEach(() => {
  vi.unstubAllGlobals();
  clearTagDefinitionsCache();
});

// In the app's theme, which is what makes a page's title its heading
const renderVersion = (schema: string, version: string) => render(
  <ThemeProvider theme={appTheme}>
    <MemoryRouter initialEntries={[ `/admin/schemas/${schema}/${version}` ]}>
      <Routes>
        <Route path="/admin/schemas/*" element={<SchemaPage />} />
      </Routes>
    </MemoryRouter>
  </ThemeProvider>,
  { wrapper: NoticeProvider },
);

// The card of the part with the given heading
const card = async (heading: string) => (await screen.findByText(heading)).closest("li") as HTMLElement;

const expand = async (heading: string) =>
  fireEvent.click(await screen.findByRole("button", { name: `Expand ${heading}` }));

describe("SchemaVersionView", () => {
  it("shows where a version stands, what can be done with it, and the outline of what it asks", async () => {
    serveSchemas();
    renderVersion("study", "v2");

    expect(await screen.findByRole("heading", { name: "Clinical study Version 2.0" })).toBeInTheDocument();
    expect(screen.getByText("Current")).toBeInTheDocument();
    expect(await screen.findByText("Active")).toBeInTheDocument();
    // The version's actions, not the schema's
    expect(await screen.findAllByRole("button", { name: "Retire" })).toHaveLength(1);
    expect(screen.queryByRole("button", { name: "Rename" })).not.toBeInTheDocument();
    // The trail leads back to the schema, by its title
    expect(getPageCrumbs()).toEqual([ { path: "/admin/schemas/study", label: "Clinical study" } ]);
    // Requirements and sections start open, questions closed
    expect(await screen.findByText("Design")).toBeInTheDocument();
    const arms = await card("Which arms does it have?");
    expect(within(arms).getByText("Any number of answers")).toBeInTheDocument();
    expect(within(arms).getByText("2 options")).toBeInTheDocument();
    expect(within(arms).getByText("Shown as list")).toBeInTheDocument();
    expect(within(await card("Lead")).getByText("1 option")).toBeInTheDocument();
    expect(screen.queryByText("Placebo")).not.toBeInTheDocument();
    // When a part applies shows without opening it
    expect(within(await card("Minimum age"))
      .getByText("Only when the answer to “Which arms does it have?” includes all of “Placebo”")).toBeInTheDocument();
    expect(screen.queryByText("Link")).not.toBeInTheDocument();
  });

  it("opens a question on what it accepts and when it is asked", async () => {
    serveSchemas();
    renderVersion("study", "v2");

    fireEvent.click(within(await card("Which arms does it have?")).getByRole("button", { name: "2 options" }));
    const options = await screen.findByRole("presentation");
    expect(within(options).getByText("Placebo")).toBeInTheDocument();
    expect(within(options).getByText(/stored as placebo/)).toBeInTheDocument();
    expect(within(options).getByText("drug")).toBeInTheDocument();
    fireEvent.keyDown(options, { key: "Escape" });
    await waitFor(() => expect(screen.queryByText("Placebo")).not.toBeInTheDocument());
    // And listed once the question is open
    await expand("Which arms does it have?");
    expect(await within(await card("Which arms does it have?")).findByText("Placebo")).toBeInTheDocument();
    await expand("Minimum age");
    expect(await screen.findByText("Between 18 and 99.")).toBeInTheDocument();
    await expand("Study code");
    expect(await screen.findByText("Must match ^[A-Z]+$.")).toBeInTheDocument();
    expect(screen.getByText("Otherwise the submitter reads “Capitals only”.")).toBeInTheDocument();
    await expand("Site");
    expect(await screen.findByText("The options are the items under /Sites.")).toBeInTheDocument();
    // Nothing more to say about a bare question, so nothing to open
    expect(screen.queryByRole("button", { name: "Expand Anything else?" })).not.toBeInTheDocument();
  });

  it("shows what each type of requirement asks for, and a type it does not know as itself", async () => {
    serveSchemas();
    renderVersion("study", "v2");

    const consent = await card("Consent form");
    expect(within(consent).getByText("Optional")).toBeInTheDocument();
    expect(within(consent).getByText("Template provided")).toBeInTheDocument();
    await expand("Consent form");
    expect(await screen.findByText("Accepts application/pdf.")).toBeInTheDocument();
    expect(within(consent).getByText(/^Only when its tag list is/)).toBeInTheDocument();
    expect(within(await card("Protocol")).getByText("Required")).toBeInTheDocument();
    await expand("Ethics approval");
    expect(await screen.findByText("Approved by reb-members.")).toBeInTheDocument();
    await expand("Sign-off");
    expect(await screen.findByText("No approver group is set.")).toBeInTheDocument();
    expect(within(await card("Audit")).getByTitle("sch/AuditRequirement")).toBeInTheDocument();
  });

  it("corrects what a question says, and re-reads the version", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v2");

    fireEvent.click(within(await card("Which arms does it have?")).getByRole("button", { name: "Edit" }));
    const dialog = await screen.findByRole("dialog", { name: /Edit question/ });
    fireEvent.change(within(dialog).getByLabelText(/Question/), { target: { value: "Which arms are there?" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v2/basics/design/arms.update.json"));
    expect(JSON.parse(posted[0].params.get("patch") ?? "")).toEqual({ text: "Which arms are there?" });
    // Nothing to correct where nothing is offered
    expect(within(await card("Minimum age")).queryByRole("button", { name: "Edit" })).not.toBeInTheDocument();
  });

  it("corrects what an option says", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v2");

    await expand("Which arms does it have?");
    const placebo = (await screen.findByText("Placebo")).closest("li") as HTMLElement;
    fireEvent.click(within(placebo).getByRole("button", { name: "Edit" }));
    const dialog = await screen.findByRole("dialog", { name: /Edit option/ });
    fireEvent.change(within(dialog).getByLabelText(/Label/), { target: { value: "Placebo arm" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v2/basics/design/arms/placebo.update.json"));
    expect(JSON.parse(posted[0].params.get("patch") ?? "")).toEqual({ label: "Placebo arm" });
    expect(screen.getByText("drug").closest("li")?.querySelector("button")).toBeNull();
  });

  it("collapses what it contains", async () => {
    serveSchemas();
    renderVersion("study", "v2");

    fireEvent.click(await screen.findByRole("button", { name: "Collapse Basic information" }));

    await waitFor(() => expect(screen.queryByText("Design")).not.toBeInTheDocument());
  });

  it("says when a version asks for nothing yet", async () => {
    serveSchemas();
    renderVersion("study", "v3");

    expect(await screen.findByText("This version asks for nothing yet.")).toBeInTheDocument();
  });

  it("says when the schema has no such version", async () => {
    serveSchemas();
    renderVersion("study", "v9");

    expect(await screen.findByText("This schema has no version v9.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Clinical study Version v9" })).toBeInTheDocument();
  });

  it("keeps saying that the schema is retired", async () => {
    serveSchemas();
    renderVersion("legacy", "v1");

    expect(await screen.findByText(/This schema is retired/)).toBeInTheDocument();
  });

  it("reports content that cannot be read", async () => {
    serveSchemas({ failContent: 500 });
    renderVersion("study", "v2");

    expect(await screen.findByText("The version's content could not be loaded")).toBeInTheDocument();
  });

  it("acts on the version, and goes back to the schema once it is discarded", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v3");

    fireEvent.click(await screen.findByRole("button", { name: "Activate" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Activate" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    fireEvent.click(await screen.findByRole("button", { name: "Discard" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Discard" }));

    expect(await screen.findByRole("gridcell", { name: "1.0" })).toBeInTheDocument();
    expect(posted.map(event => event.url))
      .toEqual([ "/Schemas/study/v3.activate.json", "/Schemas/study/v3.discard.json" ]);
  });
});
