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

import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  DataSelection,
  dataSelectionCandidate,
  type DataRequirementProjection as Projection,
} from "@iap/data-requirement/DataRequirement";

import { catalogue, collection, database, sampleCatalogue } from "./catalogue/fixtures";

// Reading a catalogue and posting a selection have their own suite, and are mocked here
const fetchCatalogue = vi.hoisted(() => vi.fn());
const saveDataSelection = vi.hoisted(() => vi.fn());

vi.mock("@iap/data-requirement/catalogueApi", () => ({ fetchCatalogue, saveDataSelection }));


function requirement(overrides: Partial<Projection> = {}): Projection {
  return {
    name: "dataNeeded",
    path: "/Schemas/study/v1/dataNeeded",
    type: "datareq/DataRequirement",
    label: "The data this study needs",
    fields: [],
    catalogueVersion: "/Catalogues/demoRegistry/v2",
    catalogueVersionLabel: "2026-06",
    ...overrides,
  };
}

function element(overrides: Partial<Projection>, disabled: boolean, onChanged: () => void) {
  return (
    <DataSelection
      path="/Submissions/one"
      requirement={requirement(overrides)}
      disabled={disabled}
      states={{}}
      onAnswered={vi.fn()}
      onChanged={onChanged}
    />);
}

function draw(overrides: Partial<Projection> = {}, disabled = false) {
  const onChanged = vi.fn();
  const { rerender } = render(element(overrides, disabled, onChanged));
  return { onChanged, again: (next: Partial<Projection>) => { rerender(element(next, disabled, onChanged)); } };
}

/** Opens the Patient collection, which starts shut, so its fields are drawn. */
async function openPatients() {
  await userEvent.click(await screen.findByRole("button", { name: "Patient collection" }));
}

const SAVE = { name: "Save selection" };

const BIRTH_DATE = { name: "birthDate" };

describe("claiming the kind", () => {
  it("offers itself for a data requirement", () => {
    expect(dataSelectionCandidate(requirement())?.[0]).toBe(DataSelection);
  });

  it("declines every other kind, so it cannot draw a requirement it knows nothing about", () => {
    expect(dataSelectionCandidate(requirement({ type: "sch/FormRequirement" }))).toBeNull();
    expect(dataSelectionCandidate(requirement({ type: "sch/DocumentRequirement" }))).toBeNull();
  });
});

describe("choosing data for a requirement", () => {
  beforeEach(() => {
    fetchCatalogue.mockResolvedValue(sampleCatalogue());
    saveDataSelection.mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("browses the version the form named, not whatever the catalogue publishes now", async () => {
    draw();

    expect(await screen.findByRole("button", { name: "records database" })).toBeInTheDocument();
    expect(fetchCatalogue).toHaveBeenCalledWith(expect.any(Function), "/Catalogues/demoRegistry/v2");
  });

  it("says which version is being chosen from", async () => {
    draw();

    expect(await screen.findByText("Choosing from 2026-06")).toBeInTheDocument();
  });

  it("says so when the catalogue has published nothing", () => {
    draw({ catalogueVersion: undefined });

    expect(screen.getByText(/published nothing to choose from/)).toBeInTheDocument();
    expect(fetchCatalogue).not.toHaveBeenCalled();
  });

  it("reports a catalogue it could not read", async () => {
    fetchCatalogue.mockRejectedValue(new Error("This catalogue could not be loaded (404)"));
    draw();

    expect(await screen.findByText("This catalogue could not be loaded (404)")).toBeInTheDocument();
  });

  it("offers nothing to save until something has been chosen", async () => {
    draw();
    await openPatients();

    expect(screen.getByRole("button", SAVE)).toBeDisabled();
  });

  it("saves the whole selection, and says the request itself has changed", async () => {
    const { onChanged } = draw();
    await openPatients();
    await userEvent.click(screen.getByRole("checkbox", BIRTH_DATE));

    await userEvent.click(screen.getByRole("button", SAVE));

    expect(saveDataSelection).toHaveBeenCalledWith(expect.any(Function), "/Submissions/one", "dataNeeded",
      [ "records/Patient/birthDate" ]);
    expect(onChanged).toHaveBeenCalled();
  });

  it("reports a refusal against the panel rather than losing it", async () => {
    saveDataSelection.mockRejectedValue(new Error("This request has been submitted"));
    const { onChanged } = draw();
    await openPatients();
    await userEvent.click(screen.getByRole("checkbox", BIRTH_DATE));

    await userEvent.click(screen.getByRole("button", SAVE));

    expect(await screen.findByText("This request has been submitted")).toBeInTheDocument();
    expect(onChanged).not.toHaveBeenCalled();
  });

  it("keeps a part-finished selection when the form is read again unchanged", async () => {
    const { again } = draw({ fields: [ "records/Patient/gender" ] });
    await openPatients();
    await userEvent.click(screen.getByRole("checkbox", BIRTH_DATE));

    // The same selection, in a new array, which is what a re-read of the form produces
    again({ fields: [ "records/Patient/gender" ] });

    expect(screen.getByRole("checkbox", BIRTH_DATE)).toBeChecked();
  });

  it("adopts a selection that really did change underneath it", async () => {
    const { again } = draw({ fields: [] });
    await openPatients();
    expect(screen.getByRole("checkbox", BIRTH_DATE)).not.toBeChecked();

    again({ fields: [ "records/Patient/birthDate" ] });

    expect(screen.getByRole("checkbox", BIRTH_DATE)).toBeChecked();
  });

  it("takes a projection that states no fields and no version label", async () => {
    draw({ fields: undefined, catalogueVersionLabel: undefined });

    expect(await screen.findByRole("button", { name: "records database" })).toBeInTheDocument();
    expect(screen.queryByText(/^Choosing from/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", SAVE)).toBeDisabled();
  });

  it("reports a rejection that is not an Error at all", async () => {
    fetchCatalogue.mockRejectedValue("the catalogue went missing");
    draw();

    expect(await screen.findByText("the catalogue went missing")).toBeInTheDocument();
  });

  it("ignores a catalogue that arrives after the requirement moved to another version", async () => {
    const other = catalogue([ database("registry", [ collection("registry", "Consent", [ "status" ]) ]) ]);
    let arriveLate!: (value: unknown) => void;
    fetchCatalogue.mockReturnValueOnce(new Promise(resolve => { arriveLate = resolve; }));
    fetchCatalogue.mockResolvedValueOnce(other);

    const { again } = draw({ catalogueVersion: "/Catalogues/demoRegistry/v1" });
    again({ catalogueVersion: "/Catalogues/demoRegistry/v2" });
    expect(await screen.findByRole("button", { name: "registry database" })).toBeInTheDocument();

    // The first read finally lands, naming a version nobody is answering against any more
    await act(async () => {
      arriveLate(sampleCatalogue());
      await Promise.resolve();
    });

    expect(screen.getByRole("button", { name: "registry database" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "records database" })).not.toBeInTheDocument();
  });
});

describe("a request that can no longer be changed", () => {
  beforeEach(() => {
    fetchCatalogue.mockResolvedValue(sampleCatalogue());
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("still shows what was chosen", async () => {
    draw({ fields: [ "records/Patient/birthDate" ] }, true);
    await openPatients();

    expect(screen.getByRole("checkbox", BIRTH_DATE)).toBeChecked();
  });

  it("offers no way to change it, and does not pretend otherwise", async () => {
    draw({ fields: [ "records/Patient/birthDate" ] }, true);
    await openPatients();

    expect(screen.getByRole("checkbox", BIRTH_DATE)).toBeDisabled();
    expect(screen.queryByRole("button", SAVE)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Remove / })).not.toBeInTheDocument();
  });
});
