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

import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";

import CatalogueManager from "@iap/data-requirement/admin/CatalogueManager";

import { serveCatalogues } from "./catalogueServer.fixture";

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
});

function Where() {
  const { pathname } = useLocation();
  return <div data-testid="where">{pathname}</div>;
}

const renderManager = () => render(
  <MemoryRouter initialEntries={[ "/admin/catalogues" ]}>
    <Routes>
      <Route path="/admin/catalogues" element={<CatalogueManager />} />
      <Route path="*" element={<Where />} />
    </Routes>
  </MemoryRouter>,
);

const rowOf = async (text: RegExp) => (await screen.findByText(text)).closest<HTMLElement>("[role='row']")!;

describe("CatalogueManager", () => {
  it("lists catalogues with where each stands", async () => {
    serveCatalogues();
    renderManager();

    expect(await screen.findByText(/Clinical registry/)).toBeInTheDocument();
    expect(await within(await rowOf(/Legacy warehouse/)).findByText("Retired")).toBeInTheDocument();
  });

  it("nests each catalogue's versions under it", async () => {
    serveCatalogues();
    renderManager();

    fireEvent.click(within(await rowOf(/Clinical registry/)).getByRole("button", { name: /see children/i }));

    expect(await screen.findByText("Version 2026-08")).toBeInTheDocument();
    expect(within(await rowOf(/Version 2026-11/)).getByText("Draft")).toBeInTheDocument();
  });

  it("opens a catalogue's page from its row", async () => {
    serveCatalogues();
    renderManager();

    fireEvent.click(await screen.findByText(/Clinical registry/));

    expect(await screen.findByTestId("where")).toHaveTextContent("/admin/catalogues/registry");
  });

  it("opens a version's page from its row", async () => {
    serveCatalogues();
    renderManager();
    fireEvent.click(within(await rowOf(/Clinical registry/)).getByRole("button", { name: /see children/i }));

    fireEvent.click(await screen.findByText("Version 2026-08"));

    expect(await screen.findByTestId("where")).toHaveTextContent("/admin/catalogues/registry/v2");
  });

  it("says when there is no catalogue", async () => {
    serveCatalogues({ homepage: { "jcr:primaryType": "datareq:CataloguesHomepage" } });
    renderManager();

    expect(await screen.findByText("No catalogues are installed.")).toBeInTheDocument();
  });
});
