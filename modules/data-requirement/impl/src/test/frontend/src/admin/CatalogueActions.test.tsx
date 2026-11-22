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

import { readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";

import CatalogueActions from "@iap/data-requirement/admin/CatalogueActions";
import CatalogueVersionActions from "@iap/data-requirement/admin/CatalogueVersionActions";
import { NoticeProvider } from "@iap/frontend-commons/components/NoticeSnackbar";

import { ACTIONS } from "./catalogueActions.fixture";
import { HOMEPAGE, serveCatalogues, withPaths } from "./catalogueServer.fixture";

vi.mock("@iap/frontend-commons/actionsManager", () => ({
  getActions: (point: string) => import("./catalogueActions.fixture").then(fixture => fixture.actionsFor(point)),
}));

afterEach(() => vi.unstubAllGlobals());

const registry = withPaths("/Catalogues/registry", HOMEPAGE.registry);
const legacy = withPaths("/Catalogues/legacy", HOMEPAGE.legacy);

const renderVersionActions = (catalogue: Record<string, unknown>, versionName: string) => {
  const reload = vi.fn();
  render(<MemoryRouter>
    <CatalogueVersionActions version={catalogue[versionName] as Record<string, unknown>} catalogue={catalogue}
      reload={reload} place="versionPage" />
  </MemoryRouter>, { wrapper: NoticeProvider });
  return { reload };
};

const renderCatalogueActions = (catalogue: Record<string, unknown>) => {
  const reload = vi.fn();
  render(<MemoryRouter><CatalogueActions catalogue={catalogue} reload={reload} /></MemoryRouter>,
    { wrapper: NoticeProvider });
  return { reload };
};

const confirm = async (label: string) => {
  const dialog = await screen.findByRole("dialog");
  fireEvent.click(within(dialog).getByRole("button", { name: label }));
  return dialog;
};

describe("the actions on a catalogue version", () => {
  it("publishes a draft once confirmed, naming the version it retires, then says so and reloads", async () => {
    const posted = serveCatalogues();
    const { reload } = renderVersionActions(registry, "v3");
    expect(screen.queryByRole("button", { name: "Retire" })).not.toBeInTheDocument();

    fireEvent.click(await screen.findByRole("button", { name: "Activate" }));
    expect(await screen.findByRole("dialog")).toHaveTextContent("Version 2026-08 is retired in the same step");
    await confirm("Activate");

    await waitFor(() => expect(reload).toHaveBeenCalled());
    expect(posted.map(event => event.url)).toEqual([ "/Catalogues/registry/v3.activate.json" ]);
    expect(await screen.findByRole("alert")).toHaveTextContent("Version 2026-11 is active");
  });

  it("says nothing is retired when no version is active", async () => {
    serveCatalogues();
    const withoutActive = withPaths("/Catalogues/registry", {
      ...HOMEPAGE.registry, v2: { ...HOMEPAGE.registry.v2, tags: [ "retired" ] },
    });
    renderVersionActions(withoutActive, "v3");

    fireEvent.click(await screen.findByRole("button", { name: "Activate" }));

    expect(await screen.findByRole("dialog")).toHaveTextContent("Nothing is retired");
  });

  it("shows why a draft cannot be published yet", async () => {
    serveCatalogues({ answers: { "/Catalogues/registry/v3.activate.json": { status: 409, error: "Not yet" } } });
    const { reload } = renderVersionActions(registry, "v3");

    fireEvent.click(await screen.findByRole("button", { name: "Activate" }));
    const dialog = await confirm("Activate");

    expect(await within(dialog).findByText("Not yet")).toBeInTheDocument();
    expect(reload).not.toHaveBeenCalled();
  });

  it("retires the active version", async () => {
    const posted = serveCatalogues();
    renderVersionActions(registry, "v2");
    expect(screen.queryByRole("button", { name: "Activate" })).not.toBeInTheDocument();

    fireEvent.click(await screen.findByRole("button", { name: "Retire" }));
    await confirm("Retire");

    await waitFor(() => expect(posted.map(event => event.url)).toEqual([ "/Catalogues/registry/v2.retire.json" ]));
    expect(await screen.findByRole("alert")).toHaveTextContent("Version 2026-08 is retired");
  });

  it("reactivates a retired version", async () => {
    const posted = serveCatalogues();
    renderVersionActions(registry, "v1");

    fireEvent.click(await screen.findByRole("button", { name: "Activate" }));
    await confirm("Activate");

    await waitFor(() => expect(posted.map(event => event.url)).toEqual([ "/Catalogues/registry/v1.activate.json" ]));
  });

  it("offers nothing on a version of a retired catalogue", async () => {
    serveCatalogues();
    renderVersionActions(legacy, "v1");
    await waitFor(() => expect(screen.queryByRole("progressbar")).not.toBeInTheDocument());

    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});

describe("the actions on a catalogue", () => {
  it("retires an open catalogue", async () => {
    const posted = serveCatalogues();
    const { reload } = renderCatalogueActions(registry);
    expect(screen.queryByRole("button", { name: "Reopen" })).not.toBeInTheDocument();

    fireEvent.click(await screen.findByRole("button", { name: "Retire" }));
    await confirm("Retire");

    await waitFor(() => expect(reload).toHaveBeenCalled());
    expect(posted.map(event => event.url)).toEqual([ "/Catalogues/registry.retire.json" ]);
    expect(await screen.findByRole("alert")).toHaveTextContent("Clinical registry is retired");
  });

  it("reopens a retired catalogue", async () => {
    const posted = serveCatalogues();
    renderCatalogueActions(legacy);
    expect(screen.queryByRole("button", { name: "Retire" })).not.toBeInTheDocument();

    fireEvent.click(await screen.findByRole("button", { name: "Reopen" }));
    await confirm("Reopen");

    await waitFor(() => expect(posted.map(event => event.url)).toEqual([ "/Catalogues/legacy.activate.json" ]));
    expect(await screen.findByRole("alert")).toHaveTextContent("Legacy warehouse is open again");
  });
});

describe("the extensions contributing the actions", () => {
  it.each(Object.keys(ACTIONS))("list the actions on %s as the stand-in for the actions manager does", point => {
    const folder = resolve(process.cwd(),
      `../../../../modules/data-requirement/impl/src/main/resources/SLING-INF/content/Extensions/${point}`);
    const assets = readdirSync(folder).map(file =>
      JSON.parse(readFileSync(resolve(folder, file), "utf8")) as Record<string, unknown>)
      .sort((one, other) => Number(one.defaultOrder) - Number(other.defaultOrder))
      .map(extension => /\.(\w+)\.js$/.exec(String(extension["ext:renderURL"]))?.[1]);
    expect(ACTIONS[point].map(action => action.asset)).toEqual(assets);
  });
});
