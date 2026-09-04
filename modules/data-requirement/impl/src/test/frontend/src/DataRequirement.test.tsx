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

import { DataSelection, dataSelectionCandidate } from "@iap/data-requirement/DataRequirement";
import type { FormRequirement } from "@iap/submissions/submissionForm";

import { catalogue, collection, database, sampleCatalogue } from "./catalogue/fixtures";

// The seam under test is the glue, not the reading of a catalogue or the shape of a POST — both have
// their own suite. Mocking exactly the two functions it calls keeps a failure here about the glue.
const fetchCatalogue = vi.hoisted(() => vi.fn());
const saveDataSelection = vi.hoisted(() => vi.fn());

vi.mock("@iap/data-requirement/catalogueApi", () => ({ fetchCatalogue, saveDataSelection }));

interface Projection extends FormRequirement {
  fields?: string[];
  catalogueVersion?: string;
  catalogueVersionLabel?: string;
}

function requirement(overrides: Partial<Projection> = {}): Projection {
  return {
    name: "dataNeeded",
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

/**
 * Opens a collection so its fields are drawn.
 *
 * The catalogue opens with every database expanded and every collection shut, so a field row does
 * not exist until its collection is opened — which is a property of the browser rather than of this
 * glue, and the reason a test reaching straight for a checkbox waits for one that never arrives.
 */
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
    expect(fetchCatalogue).toHaveBeenCalledWith("/Catalogues/demoRegistry/v2");
  });

  it("says which version is being chosen from", async () => {
    draw();

    expect(await screen.findByText("Choosing from 2026-06")).toBeInTheDocument();
  });

  // A requirement pointing at a catalogue that has published nothing is not a broken form: it is a
  // catalogue nobody has published yet, and saying so beats an empty tree that looks like a failure.
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

    expect(saveDataSelection).toHaveBeenCalledWith("/Submissions/one", "dataNeeded",
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

  // The trap the answer field already paid for: every read of the form builds fresh arrays, so a
  // component following its value by identity resets itself whenever anything else on the form is
  // saved — including while somebody is still choosing here.
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

  // Reading a projection that states neither: a requirement nobody has answered may carry no field
  // list at all, and a version may have no label worth printing. Neither is a broken form.
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

  // The guard on the late response is observable rather than merely covered: a slow read of one
  // version must not overwrite a faster read of the version that replaced it, or the tree ends up
  // showing a catalogue the requirement is no longer being answered against.
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

  // Not merely refusing the write: a control that still looks pressable and quietly does nothing is
  // worse than one that plainly cannot be used.
  it("offers no way to change it, and does not pretend otherwise", async () => {
    draw({ fields: [ "records/Patient/birthDate" ] }, true);
    await openPatients();

    expect(screen.getByRole("checkbox", BIRTH_DATE)).toBeDisabled();
    expect(screen.queryByRole("button", SAVE)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Remove / })).not.toBeInTheDocument();
  });
});
