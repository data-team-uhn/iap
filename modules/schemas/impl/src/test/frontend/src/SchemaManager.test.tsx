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

import { NoticeProvider } from "@iap/frontend-commons/components/NoticeSnackbar";
import SchemaManager from "@iap/schemas/SchemaManager";
import { clearTagDefinitionsCache } from "@iap/tags/tagDefinitions";

import { serveSchemas } from "./schemaServer.fixture";

vi.mock("@iap/frontend-commons/actionsManager", () => ({
  getActions: (point: string, place?: string) =>
    import("./actions.fixture").then(fixture => fixture.actionsFor(point, place)),
}));

afterEach(() => {
  vi.unstubAllGlobals();
  clearTagDefinitionsCache();
  window.localStorage.clear();
});

function Where() {
  const { pathname, search } = useLocation();
  return <div data-testid="where">{pathname + search}</div>;
}

const renderManager = () => render(
  <MemoryRouter initialEntries={[ "/admin/schemas" ]}>
    <Routes>
      <Route path="/admin/schemas" element={<SchemaManager />} />
      <Route path="*" element={<Where />} />
    </Routes>
  </MemoryRouter>,
  { wrapper: NoticeProvider },
);

// Makes MUI's useMediaQuery see a phone, switching the grid to its card list
function fakeNarrowScreen() {
  vi.stubGlobal("matchMedia", (query: string) => ({
    matches: query.includes("max-width"), media: query, onchange: null,
    addEventListener: () => undefined, removeEventListener: () => undefined,
    addListener: () => undefined, removeListener: () => undefined, dispatchEvent: () => false,
  }));
}

const expand = async (title: RegExp) => {
  const row = (await screen.findByText(title)).closest<HTMLElement>("[role='row']")!;
  fireEvent.click(within(row).getByRole("button", { name: /see children/i }));
};

const fillNewSchema = async (title: string) => {
  fireEvent.click(await screen.findByRole("button", { name: "New schema" }));
  const dialog = await screen.findByRole("dialog");
  fireEvent.change(within(dialog).getByLabelText(/Title/), { target: { value: title } });
  return dialog;
};

describe("SchemaManager", () => {
  it("lists schemas with their versions nested under them", async () => {
    serveSchemas();
    renderManager();

    expect(await screen.findByText(/Clinical study/)).toBeInTheDocument();
    const legacy = (await screen.findByText(/Legacy/)).closest<HTMLElement>("[role='row']")!;
    expect(await within(legacy).findByText("Retired")).toBeInTheDocument();
    await expand(/Clinical study/);
    const draft = (await screen.findByText("Version 3.0")).closest<HTMLElement>("[role='row']")!;
    expect(await within(draft).findByText("Draft")).toBeInTheDocument();
    expect(await within(draft).findByRole("button", { name: "Activate" })).toBeInTheDocument();
  });

  it("opens a version's page from its row", async () => {
    serveSchemas();
    renderManager();

    await expand(/Clinical study/);
    fireEvent.click(await screen.findByText("Version 2.0"));

    expect(await screen.findByTestId("where")).toHaveTextContent("/admin/schemas/study/v2");
  });

  it("acts on a version without leaving the listing", async () => {
    const posted = serveSchemas();
    renderManager();

    await expand(/Clinical study/);
    const active = (await screen.findByText("Version 2.0")).closest<HTMLElement>("[role='row']")!;
    fireEvent.click(await within(active).findByRole("button", { name: "Retire" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Retire" }));

    await waitFor(() => expect(posted.map(event => event.url)).toContain("/Schemas/study/v2.retire.json"));
    expect(await screen.findByText("Version 2.0 is retired")).toBeInTheDocument();
    expect(screen.queryByTestId("where")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Dismiss" }));
    await waitFor(() => expect(screen.queryByText("Version 2.0 is retired")).not.toBeInTheDocument());
  });

  it("names an unlabelled version by its node, and opens a schema from its own row", async () => {
    serveSchemas({ homepage: { "tagged": { "jcr:primaryType": "sch:Schema", "title": "Tagged",
      "tags": [ "sensitive" ], "v7": { "jcr:primaryType": "sch:SchemaVersion", "tags": [ "draft" ] } } } });
    renderManager();

    await expand(/Tagged/);
    expect(await screen.findByText("Version v7")).toBeInTheDocument();
    expect(screen.queryByText("Retired")).not.toBeInTheDocument();
    fireEvent.click(screen.getByText(/Tagged/));
    expect(await screen.findByTestId("where")).toHaveTextContent("/admin/schemas/tagged");
  });

  it("renames and retires a schema from its row", async () => {
    const posted = serveSchemas();
    renderManager();

    const study = (await screen.findByText(/Clinical study/)).closest<HTMLElement>("[role='row']")!;
    fireEvent.click(await within(study).findByRole("button", { name: "Rename" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/Title/), { target: { value: "Clinical trial" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study.update.json"));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    fireEvent.click(await within(study).findByRole("button", { name: "Retire" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Retire" }));

    await waitFor(() => expect(posted.map(event => event.url)).toContain("/Schemas/study.retire.json"));
    expect(await screen.findByText("Clinical study is retired")).toBeInTheDocument();
    expect(screen.queryByTestId("where")).not.toBeInTheDocument();
  });

  it("reopens a retired schema from its row", async () => {
    const posted = serveSchemas();
    renderManager();

    const legacy = (await screen.findByText(/Legacy/)).closest<HTMLElement>("[role='row']")!;
    fireEvent.click(await within(legacy).findByRole("button", { name: "Reopen" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Reopen" }));

    await waitFor(() => expect(posted.map(event => event.url)).toContain("/Schemas/legacy.activate.json"));
    expect(await screen.findByText("Legacy is open again")).toBeInTheDocument();
  });

  it("discards a schema from its row, and stays on the listing", async () => {
    const posted = serveSchemas();
    renderManager();

    const idea = (await screen.findByText(/Idea/)).closest<HTMLElement>("[role='row']")!;
    fireEvent.click(await within(idea).findByRole("button", { name: "Discard" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Discard" }));

    await waitFor(() => expect(posted.map(event => event.url)).toContain("/Schemas/idea.discard.json"));
    expect(await screen.findByText("Idea is discarded")).toBeInTheDocument();
    expect(screen.queryByTestId("where")).not.toBeInTheDocument();
  });

  it("shows each schema as a card on a phone", async () => {
    fakeNarrowScreen();
    serveSchemas({ homepage: { "empty": { "jcr:primaryType": "sch:Schema", "title": "Empty" },
      "study": { "jcr:primaryType": "sch:Schema", "title": "Clinical study", "tags": [ "retired" ],
        "v1": { "jcr:primaryType": "sch:SchemaVersion", "version": "1.0", "tags": [ "active" ] } } } });
    renderManager();

    expect(await screen.findByText("Clinical study")).toBeInTheDocument();
    expect(screen.getByText("1.0")).toBeInTheDocument();
    expect(screen.getByText("No versions")).toBeInTheDocument();
  });

  it("creates a schema and opens its page", async () => {
    const posted = serveSchemas({ answers: { "/Schemas.create.json": { redirect: "/Schemas/trial" } } });
    renderManager();

    const dialog = await fillNewSchema("  Trial ");
    fireEvent.change(within(dialog).getByLabelText(/First version/), { target: { value: "2026" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Create" }));

    expect(await screen.findByTestId("where")).toHaveTextContent("/admin/schemas/trial");
    expect(posted[0].params.get("title")).toBe("Trial");
    expect(posted[0].params.get("version")).toBe("2026");
    expect(posted[0].params.get("source")).toBeNull();
  });

  it("starts a schema as a copy of any schema's version", async () => {
    const posted = serveSchemas({ answers: { "/Schemas.create.json": { redirect: "/Schemas/trial" } } });
    renderManager();

    const dialog = await fillNewSchema("Trial");
    fireEvent.mouseDown(within(dialog).getByRole("combobox", { name: "Start from" }));
    // Grouped under their schemas
    expect(await screen.findByRole("option", { name: /A copy of version 2\.0/ })).toBeInTheDocument();
    expect(screen.getByText("Idea", { selector: "li" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("option", { name: /A copy of version 2\.0/ }));
    expect(within(dialog).getByRole("combobox", { name: "Start from" }))
      .toHaveTextContent("A copy of version 2.0 of Clinical study");
    fireEvent.click(within(dialog).getByRole("button", { name: "Create" }));

    expect(await screen.findByTestId("where")).toHaveTextContent("/admin/schemas/trial");
    expect(posted[0].params.get("source")).toBe("/Schemas/study/v2");
  });

  it("keeps the dialog open with the engine's reason when creation is refused", async () => {
    serveSchemas({ answers: { "/Schemas.create.json": { status: 403, error: "You are not allowed to do this" } } });
    renderManager();

    const dialog = await fillNewSchema("Trial");
    fireEvent.click(within(dialog).getByRole("button", { name: "Create" }));

    expect(await within(dialog).findByText("You are not allowed to do this")).toBeInTheDocument();
  });

  it("just closes when the engine accepts without creating anything, or on cancel", async () => {
    serveSchemas();
    renderManager();

    let dialog = await fillNewSchema("Trial");
    fireEvent.click(within(dialog).getByRole("button", { name: "Create" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    dialog = await fillNewSchema("Other");
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });
});
