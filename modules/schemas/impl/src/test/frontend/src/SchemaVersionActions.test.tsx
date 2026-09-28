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
import { MemoryRouter } from "react-router";

import { NoticeProvider } from "@iap/frontend-commons/components/NoticeSnackbar";
import SchemaVersionActions from "@iap/schemas/SchemaVersionActions";

import { BUILTIN_ACTIONS } from "./actions.fixture";
import { HOMEPAGE, serveSchemas, withPaths } from "./schemaServer.fixture";

vi.mock("@iap/frontend-commons/actionsManager", () => ({
  getActions: (point: string) => import("./actions.fixture").then(fixture => fixture.actionsFor(point)),
}));

afterEach(() => vi.unstubAllGlobals());

const study = withPaths("/Schemas/study", HOMEPAGE.study);

const renderActions = (versionName: string) => {
  const reload = vi.fn();
  const version = study[versionName] as Record<string, unknown>;
  render(<MemoryRouter>
    <SchemaVersionActions version={version} schema={study} reload={reload} />
  </MemoryRouter>, { wrapper: NoticeProvider });
  return { reload };
};

const confirm = async (label: string) => {
  const dialog = await screen.findByRole("dialog");
  fireEvent.click(within(dialog).getByRole("button", { name: label }));
  return dialog;
};

describe("SchemaVersionActions", () => {
  it("offers what each state allows", async () => {
    expect(BUILTIN_ACTIONS).toHaveLength(4);
    renderActions("v3");
    expect(await screen.findByRole("button", { name: "Activate" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Discard" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Retire" })).not.toBeInTheDocument();
  });

  it("renders nothing if it goes away before its actions arrive", async () => {
    const { unmount } = render(<MemoryRouter>
      <SchemaVersionActions version={study.v1 as Record<string, unknown>} schema={study} reload={vi.fn()} />
    </MemoryRouter>);
    unmount();
    await Promise.resolve();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("publishes a draft once confirmed, then says so and reloads", async () => {
    const posted = serveSchemas();
    const { reload } = renderActions("v3");

    fireEvent.click(await screen.findByRole("button", { name: "Activate" }));
    await confirm("Activate");

    await waitFor(() => expect(reload).toHaveBeenCalled());
    expect(posted[0].url).toBe("/Schemas/study/v3.activate.json");
    expect(await screen.findByRole("alert")).toHaveTextContent("Version 3.0 is active");
  });

  it("shows why a draft cannot be published yet", async () => {
    serveSchemas({ answers: { "/Schemas/study/v3.activate.json": { status: 409, error: "Not yet: bad pattern" } } });
    const { reload } = renderActions("v3");

    fireEvent.click(await screen.findByRole("button", { name: "Activate" }));
    const dialog = await confirm("Activate");

    expect(await within(dialog).findByText("Not yet: bad pattern")).toBeInTheDocument();
    expect(reload).not.toHaveBeenCalled();
  });

  it("retires an active version", async () => {
    const posted = serveSchemas();
    renderActions("v2");
    fireEvent.click(await screen.findByRole("button", { name: "Retire" }));
    await confirm("Retire");
    await waitFor(() => expect(posted.map(event => event.url)).toContain("/Schemas/study/v2.retire.json"));
  });

  it("reactivates a retired version", async () => {
    const posted = serveSchemas();
    renderActions("v1");
    fireEvent.click(await screen.findByRole("button", { name: "Activate" }));
    await confirm("Activate");
    await waitFor(() => expect(posted.map(event => event.url)).toContain("/Schemas/study/v1.activate.json"));
  });

  it("discards a draft", async () => {
    const posted = serveSchemas();
    renderActions("v3");
    fireEvent.click(await screen.findByRole("button", { name: "Discard" }));
    await confirm("Discard");
    await waitFor(() => expect(posted.map(event => event.url)).toContain("/Schemas/study/v3.discard.json"));
  });

  it("edits a draft's details", async () => {
    const posted = serveSchemas();
    const { reload } = renderActions("v3");
    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/Label/), { target: { value: "3.1" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(reload).toHaveBeenCalled());
    expect(posted[0].url).toBe("/Schemas/study/v3.update.json");
    expect(JSON.parse(posted[0].params.get("patch") ?? "")).toEqual({ version: "3.1" });
  });

  it("offers only the fields the update would change", async () => {
    serveSchemas();
    renderActions("v2");
    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    const published = await screen.findByRole("dialog");
    // A published version keeps its label: its update accepts only the description
    expect(within(published).queryByLabelText(/Label/)).not.toBeInTheDocument();
    expect(within(published).getByLabelText(/Description/)).toBeInTheDocument();
    fireEvent.click(within(published).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("picks the workflow a draft follows among the workflows", async () => {
    const posted = serveSchemas();
    renderActions("v3");
    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    const draft = await screen.findByRole("dialog");
    fireEvent.mouseDown(within(draft).getByRole("combobox", { name: "Workflow" }));
    // Named by their title, or else by where they are under the workflows
    expect(await screen.findByRole("option", { name: "review/v1" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("option", { name: "Fast track" }));
    fireEvent.click(within(draft).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(posted).toHaveLength(1));
    expect(JSON.parse(posted[0].params.get("patch") ?? "")).toEqual({ workflow: "/Workflows/fastTrack/v2" });
  });
});
