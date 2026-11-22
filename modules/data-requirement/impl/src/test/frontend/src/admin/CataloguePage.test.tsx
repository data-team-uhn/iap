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
import { MemoryRouter, Route, Routes } from "react-router";

import CataloguePage from "@iap/data-requirement/admin/CataloguePage";
import { NoticeProvider } from "@iap/frontend-commons/components/NoticeSnackbar";

import { serveCatalogues } from "./catalogueServer.fixture";

vi.mock("@iap/frontend-commons/actionsManager", () => ({
  getActions: (point: string) => import("./catalogueActions.fixture").then(fixture => fixture.actionsFor(point)),
}));

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

const renderPage = (path: string) => render(
  <MemoryRouter initialEntries={[ path ]}>
    <Routes>
      <Route path="/admin/catalogues/*" element={<CataloguePage />} />
    </Routes>
  </MemoryRouter>,
  { wrapper: NoticeProvider },
);

const rowOf = async (text: string) => (await screen.findByText(text)).closest<HTMLElement>("[role='row']")!;

describe("CataloguePage", () => {
  it("lists a catalogue's versions with where each stands and what can be done with it", async () => {
    serveCatalogues();
    renderPage("/admin/catalogues/registry");

    expect(await screen.findByText("Clinical registry")).toBeInTheDocument();
    const current = await rowOf("2026-08");
    expect(within(current).getByText("Current")).toBeInTheDocument();
    expect(within(current).getByText("Active")).toBeInTheDocument();
    expect(await within(current).findByRole("button", { name: "Retire" })).toBeInTheDocument();
    const draft = await rowOf("2026-11");
    expect(within(draft).getByText("Draft")).toBeInTheDocument();
    expect(within(draft).getByRole("button", { name: "Activate" })).toBeInTheDocument();
    expect(screen.queryByText(/This catalogue is retired/)).not.toBeInTheDocument();
  });

  it("offers the actions on the catalogue itself", async () => {
    serveCatalogues();
    renderPage("/admin/catalogues/registry");

    await rowOf("2026-08");

    // The catalogue's own actions come first, in the heading
    expect((await screen.findAllByRole("button", { name: "Retire" }))[0].closest("[role='row']")).toBeNull();
  });

  it("says when the catalogue is retired", async () => {
    serveCatalogues();
    renderPage("/admin/catalogues/legacy");

    expect(await screen.findByText(/This catalogue is retired/)).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Reopen" })).toBeInTheDocument();
  });

  it("shows a version's fields to browse, but not to choose", async () => {
    serveCatalogues();
    renderPage("/admin/catalogues/registry/v2");

    expect(await screen.findByText("Version 2026-08")).toBeInTheDocument();
    expect(screen.getByText("Clinical registry")).toBeInTheDocument();
    expect(screen.getByText("Current")).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Patient records database" })).toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: "Patient collection" }));
    expect(await screen.findByRole("checkbox", { name: "Date of birth" })).toBeDisabled();
  });

  it("offers the actions on a version on its page", async () => {
    const posted = serveCatalogues();
    renderPage("/admin/catalogues/registry/v3");

    fireEvent.click(await screen.findByRole("button", { name: "Activate" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Activate" }));

    await waitFor(() => expect(posted.map(event => event.url)).toEqual([ "/Catalogues/registry/v3.activate.json" ]));
  });

  it("says on a version's page when its catalogue is retired", async () => {
    serveCatalogues();
    renderPage("/admin/catalogues/legacy/v1");

    expect(await screen.findByText(/This catalogue is retired/)).toBeInTheDocument();
    expect(await screen.findByText("Version 1")).toBeInTheDocument();
  });

  it("says when a version's fields could not be read", async () => {
    serveCatalogues({ failContent: 500 });
    renderPage("/admin/catalogues/registry/v2");

    expect(await screen.findByText("Version 2026-08")).toBeInTheDocument();
    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });

  it("says when the catalogue has no such version", async () => {
    serveCatalogues();
    renderPage("/admin/catalogues/registry/v9");

    expect(await screen.findByText("This catalogue has no version v9.")).toBeInTheDocument();
  });

  it("says when the catalogue could not be loaded", async () => {
    serveCatalogues({ failReads: 500 });
    renderPage("/admin/catalogues/registry");

    expect(await screen.findByText("The catalogue could not be loaded")).toBeInTheDocument();
  });
});
