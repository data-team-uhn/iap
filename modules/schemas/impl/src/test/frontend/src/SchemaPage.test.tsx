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

import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";

import SchemaPage from "@iap/schemas/SchemaPage";
import { clearTagDefinitionsCache } from "@iap/tags/tagDefinitions";

import { HOMEPAGE, serveSchemas } from "./schemaServer.fixture";

vi.mock("@iap/frontend-commons/actionsManager", () => ({
  getActions: (point: string) => import("./actions.fixture").then(fixture => fixture.actionsFor(point)),
}));

afterEach(() => {
  vi.unstubAllGlobals();
  clearTagDefinitionsCache();
});

function Where() {
  return <div data-testid="where">{useLocation().pathname}</div>;
}

const renderPage = (name: string) => render(
  <MemoryRouter initialEntries={[ `/admin/schemas/${name}` ]}>
    <Routes>
      <Route path="/admin/schemas/*" element={<SchemaPage />} />
      <Route path="/admin/schemas" element={<Where />} />
    </Routes>
  </MemoryRouter>,
);

// The row of the version with the given label
const versionRow = async (label: string) =>
  (await screen.findByRole("gridcell", { name: label })).closest<HTMLElement>("[role='row']")!;

const confirm = async (label: string) => {
  const dialog = await screen.findByRole("dialog");
  fireEvent.click(within(dialog).getByRole("button", { name: label }));
  return dialog;
};

describe("SchemaPage", () => {
  it("shows the schema's versions with the actions the server offers on each", async () => {
    serveSchemas();
    renderPage("study");

    expect(await screen.findByText("Clinical study")).toBeInTheDocument();
    const retired = await versionRow("1.0");
    expect(await within(retired).findByRole("button", { name: "Activate" })).toBeInTheDocument();
    const active = await versionRow("2.0");
    expect(within(active).getByText("Current")).toBeInTheDocument();
    expect(within(active).getByRole("button", { name: "Retire" })).toBeInTheDocument();
    const draft = await versionRow("3.0");
    expect(within(draft).getByRole("button", { name: "Activate" })).toBeInTheDocument();
    expect(within(draft).getByRole("button", { name: "Discard" })).toBeInTheDocument();
    // Discarding the schema is one of them too
    expect(screen.getAllByRole("button", { name: "Discard" })[0].closest("[role='row']")).toBeNull();
    // The schema's own actions come first, in the heading
    expect((await screen.findAllByRole("button", { name: "Retire" }))[0].closest("[role='row']")).toBeNull();
  });

  it("publishes a draft once confirmed", async () => {
    const posted = serveSchemas();
    renderPage("study");

    fireEvent.click(await within(await versionRow("3.0")).findByRole("button", { name: "Activate" }));
    expect(within(await screen.findByRole("dialog")).getByText(/only its wording can change/)).toBeInTheDocument();
    await confirm("Activate");

    await waitFor(() => expect(posted.map(event => event.url)).toContain("/Schemas/study/v3.activate.json"));
  });

  it("shows why a draft cannot be published yet", async () => {
    serveSchemas({ answers: { "/Schemas/study/v3.activate.json": {
      status: 409, error: "Version 3.0 cannot be activated yet: \"Age\" has a pattern that is not valid." } } });
    renderPage("study");

    fireEvent.click(await within(await versionRow("3.0")).findByRole("button", { name: "Activate" }));
    const dialog = await confirm("Activate");

    expect(await within(dialog).findByText(/cannot be activated yet/)).toBeInTheDocument();
  });

  it("retires, reactivates and discards versions", async () => {
    const posted = serveSchemas();
    renderPage("study");

    fireEvent.click(await within(await versionRow("2.0")).findByRole("button", { name: "Retire" }));
    await confirm("Retire");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    fireEvent.click(await within(await versionRow("1.0")).findByRole("button", { name: "Activate" }));
    await confirm("Activate");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    fireEvent.click(await within(await versionRow("3.0")).findByRole("button", { name: "Discard" }));
    await confirm("Discard");

    await waitFor(() => expect(posted.map(event => event.url)).toEqual([
      "/Schemas/study/v2.retire.json", "/Schemas/study/v1.activate.json", "/Schemas/study/v3.discard.json",
    ]));
    // Each reported; the report can be dismissed
    fireEvent.click((await screen.findAllByRole("button", { name: "Dismiss" }))[0]);
  });

  it("edits a draft's details, sending only what changed", async () => {
    const posted = serveSchemas();
    renderPage("study");

    fireEvent.click(await within(await versionRow("3.0")).findByRole("button", { name: "Edit" }));
    const dialog = await screen.findByRole("dialog");
    const save = within(dialog).getByRole("button", { name: "Save" });
    expect(save).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText(/Label/), { target: { value: "3.1" } });
    fireEvent.change(within(dialog).getByLabelText(/Description/), { target: { value: "Next" } });
    fireEvent.click(save);

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v3.update.json"));
    expect(JSON.parse(posted[0].params.get("patch") ?? "")).toEqual({ version: "3.1", description: "Next" });
  });

  it("removes an emptied description", async () => {
    const posted = serveSchemas();
    renderPage("study");

    fireEvent.click(await within(await versionRow("2.0")).findByRole("button", { name: "Edit" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/Description/), { target: { value: " " } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v2.update.json"));
    expect(JSON.parse(posted[0].params.get("patch") ?? "")).toEqual({ description: null });
  });

  it("will not save a required field left empty, and keeps a refused edit open", async () => {
    serveSchemas({ answers: { "/Schemas/study.update.json": { status: 400, error: "title cannot be empty" } } });
    renderPage("study");

    fireEvent.click(await screen.findByRole("button", { name: "Rename" }));
    const dialog = await screen.findByRole("dialog");
    const title = within(dialog).getByLabelText(/Title/);
    fireEvent.change(title, { target: { value: "" } });
    expect(within(dialog).getByRole("button", { name: "Save" })).toBeDisabled();
    fireEvent.change(title, { target: { value: "Renamed" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    expect(await within(dialog).findByText("title cannot be empty")).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("renames the schema", async () => {
    const posted = serveSchemas();
    renderPage("study");

    fireEvent.click(await screen.findByRole("button", { name: "Rename" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/Title/), { target: { value: "Clinical trial" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study.update.json"));
    expect(JSON.parse(posted[0].params.get("patch") ?? "")).toEqual({ title: "Clinical trial" });
  });

  it("retires the schema as a whole", async () => {
    const posted = serveSchemas();
    renderPage("study");

    fireEvent.click((await screen.findAllByRole("button", { name: "Retire" }))[0]);
    await confirm("Retire");

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study.retire.json"));
  });

  it("reopens a retired schema", async () => {
    const posted = serveSchemas();
    renderPage("legacy");

    expect(await screen.findByText(/This schema is retired/)).toBeInTheDocument();
    // Its tags are shown beside its title
    expect(await screen.findByText("Retired")).toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: "Reopen" }));
    await confirm("Reopen");

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/legacy.activate.json"));
  });

  it("discards a schema, and goes back to the list", async () => {
    const posted = serveSchemas();
    renderPage("idea");

    fireEvent.click((await screen.findAllByRole("button", { name: "Discard" }))[0]);
    await confirm("Discard");

    expect(await screen.findByTestId("where")).toHaveTextContent("/admin/schemas");
    expect(posted[0].url).toBe("/Schemas/idea.discard.json");
  });

  it("adds a version as a copy of the one made last, and opens it", async () => {
    const posted = serveSchemas({ answers: { "/Schemas/study.createVersion.json": { redirect: "/Schemas/study/v4" } } });
    renderPage("study");

    fireEvent.click(await screen.findByRole("button", { name: "New version" }));
    const dialog = await screen.findByRole("dialog");
    const label = within(dialog).getByLabelText(/Label/);
    expect(label).toHaveValue("4.0");
    expect(within(dialog).getByRole("combobox", { name: "Start from" })).toHaveTextContent("A copy of version 3.0");
    fireEvent.change(label, { target: { value: " 3.1 " } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Create" }));

    expect(await screen.findByText("This schema has no version v4.")).toBeInTheDocument();
    expect(posted[0].url).toBe("/Schemas/study.createVersion.json");
    expect(posted[0].params.get("version")).toBe("3.1");
    expect(posted[0].params.get("source")).toBe("/Schemas/study/v3");
    expect(await screen.findByText("Version 3.1 is created")).toBeInTheDocument();
  });

  it("adds an empty version, and stays when nothing is reported created", async () => {
    const posted = serveSchemas();
    renderPage("study");

    fireEvent.click(await screen.findByRole("button", { name: "New version" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.mouseDown(within(dialog).getByRole("combobox", { name: "Start from" }));
    fireEvent.click(await screen.findByRole("option", { name: "An empty version" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Create" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(posted[0].params.get("source")).toBeNull();
    expect(screen.getByText("Clinical study")).toBeInTheDocument();
  });

  it("offers no new version on a retired schema", async () => {
    serveSchemas();
    renderPage("legacy");

    expect(await screen.findByRole("button", { name: "Reopen" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "New version" })).not.toBeInTheDocument();
  });

  it("starts the first version of a schema that has none empty, and can be cancelled", async () => {
    serveSchemas({ homepage: { ...HOMEPAGE, "blank": {
      "jcr:primaryType": "sch:Schema", "title": "Blank", "@events": [ "createVersion" ] } } });
    renderPage("blank");

    fireEvent.click(await screen.findByRole("button", { name: "New version" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByLabelText(/Label/)).toHaveValue("1.0");
    expect(within(dialog).getByRole("combobox", { name: "Start from" })).toHaveTextContent("An empty version");
    fireEvent.change(within(dialog).getByLabelText(/Label/), { target: { value: " " } });
    expect(within(dialog).getByRole("button", { name: "Create" })).toBeDisabled();
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("opens a version from its row, and sorts versions by label as numbers", async () => {
    serveSchemas({ homepage: { ...HOMEPAGE, "many": { "jcr:primaryType": "sch:Schema", "title": "Many",
      "v10": { "jcr:primaryType": "sch:SchemaVersion", "version": "10.0", "tags": [ "draft" ] },
      "v2": { "jcr:primaryType": "sch:SchemaVersion", "version": "2.0", "description": "Second", "tags": [ "active" ] },
    } } });
    renderPage("many");

    await versionRow("10.0");
    fireEvent.click(screen.getByRole("columnheader", { name: "Version" }));
    await waitFor(() => expect(screen.getAllByRole("row")[1]).toHaveTextContent("2.0"));
    fireEvent.click(screen.getByRole("gridcell", { name: "2.0" }));

    await waitFor(() => expect(screen.queryByRole("grid")).not.toBeInTheDocument());
    expect(await screen.findByText("Second")).toBeInTheDocument();
  });

  it("lists a card per version on a phone", async () => {
    vi.stubGlobal("matchMedia", (query: string) => ({
      matches: query.includes("max-width"), media: query, onchange: null,
      addEventListener: () => undefined, removeEventListener: () => undefined,
      addListener: () => undefined, removeListener: () => undefined, dispatchEvent: () => false,
    }));
    serveSchemas();
    renderPage("study");

    expect(await screen.findByText("Version 3.0")).toBeInTheDocument();
    expect(screen.getByText("Current")).toBeInTheDocument();
  });

  it("says when a schema has no versions", async () => {
    serveSchemas({ homepage: { ...HOMEPAGE, "empty": { "jcr:primaryType": "sch:Schema", "title": "Empty" } } });
    renderPage("empty");

    expect(await screen.findByText("This schema has no versions.")).toBeInTheDocument();
  });

  it("reports a schema that cannot be read", async () => {
    serveSchemas();
    renderPage("missing");

    expect(await screen.findByText("The schema could not be loaded")).toBeInTheDocument();
  });

  it("keeps the schema on screen when a re-read fails", async () => {
    serveSchemas();
    renderPage("study");
    fireEvent.click(await within(await versionRow("2.0")).findByRole("button", { name: "Retire" }));
    serveSchemas({ failReads: 500 });
    await confirm("Retire");

    expect(await screen.findByText("The schema could not be reloaded")).toBeInTheDocument();
    expect(screen.getByText("Clinical study")).toBeInTheDocument();
  });
});
