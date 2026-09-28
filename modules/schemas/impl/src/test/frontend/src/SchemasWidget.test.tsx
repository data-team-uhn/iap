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

import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";

import SchemasWidget from "@iap/schemas/SchemasWidget";
import { clearTagDefinitionsCache } from "@iap/tags/tagDefinitions";

import { serveSchemas } from "./schemaServer.fixture";

afterEach(() => {
  vi.unstubAllGlobals();
  clearTagDefinitionsCache();
});

const renderWidget = () => render(<MemoryRouter><SchemasWidget /></MemoryRouter>);

describe("SchemasWidget", () => {
  it("counts the active and draft versions, and the retired schemas", async () => {
    serveSchemas();
    renderWidget();

    expect(screen.getByLabelText("Loading the schemas")).toBeInTheDocument();
    const active = await screen.findByText("Active versions");
    expect(active.parentElement).toHaveTextContent("2");
    expect(screen.getByText("Draft versions in progress").parentElement).toHaveTextContent("2");
    expect(screen.getByText("Retired schemas").parentElement).toHaveTextContent("1");
  });

  it("says so when the schemas cannot be read", async () => {
    serveSchemas({ failReads: 500 });
    renderWidget();

    expect(await screen.findByText("The schemas could not be loaded.")).toBeInTheDocument();
  });
});
