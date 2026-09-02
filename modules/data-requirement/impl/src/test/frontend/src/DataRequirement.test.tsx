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

import type { Catalogue } from "@iap/data-requirement/catalogue/types";
import { readCatalogue } from "@iap/data-requirement/catalogueApi";
import {
  DataSelection,
  dataSelectionCandidate,
  type DataRequirementProjection as Projection,
} from "@iap/data-requirement/DataRequirement";

import { sampleCatalogue } from "./catalogue/catalogue.fixture";

// Reading a node and posting a selection have their own suites, and are mocked here
const useNode = vi.hoisted(() => vi.fn());
const saveDataSelection = vi.hoisted(() => vi.fn());

vi.mock("@iap/frontend-commons/useNode", () => ({ useNode }));
vi.mock("@iap/data-requirement/catalogueApi", async original => ({
  ...await original<typeof import("@iap/data-requirement/catalogueApi")>(),
  saveDataSelection,
}));

const BIRTH_DATE_KEY = "records/Patient/birthDate";
const GENDER_KEY = "records/Patient/gender";
const BIRTH_DATE = { name: "birthDate" };
const GENDER = { name: "gender" };

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

/** What `useNode` answers: the catalogue it read, or why it could not. */
function nodeRead(read: { value?: Catalogue; loadError?: string }) {
  useNode.mockReturnValue({ loading: false, reload: vi.fn(), doFetch: vi.fn(), ...read });
}

/** A queue that runs each save at once. */
function runningQueue() {
  return vi.fn().mockImplementation((start: () => Promise<unknown>) => start());
}

/** A queue that holds every save until the test starts it. */
function heldQueue() {
  const held: (() => Promise<unknown>)[] = [];
  const queueSave = vi.fn().mockImplementation((start: () => Promise<unknown>) => {
    held.push(start);
    return new Promise(() => undefined);
  });
  return { queueSave, held };
}

function element(overrides: Partial<Projection>, disabled: boolean, queueSave = runningQueue(), onChanged = vi.fn()) {
  return (
    <DataSelection
      path="/Submissions/one"
      requirement={requirement(overrides)}
      disabled={disabled}
      states={{}}
      onAnswered={vi.fn()}
      queueSave={queueSave}
      onChanged={onChanged}
    />);
}

function draw(overrides: Partial<Projection> = {}, queueSave = runningQueue()) {
  const onChanged = vi.fn();
  const { rerender } = render(element(overrides, false, queueSave, onChanged));
  return { onChanged, again: (next: Partial<Projection>) => { rerender(element(next, false, queueSave, onChanged)); } };
}

function drawLocked(overrides: Partial<Projection> = {}) {
  render(element(overrides, true));
}

/** Opens the Patient collection, which starts shut, so its fields are drawn. */
async function openPatients() {
  await userEvent.click(await screen.findByRole("button", { name: "Patient collection" }));
}

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
    nodeRead({ value: sampleCatalogue() });
    saveDataSelection.mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("browses the version the form named, read three levels down", async () => {
    draw();

    expect(await screen.findByRole("button", { name: "records database" })).toBeInTheDocument();
    expect(useNode).toHaveBeenCalledWith("/Catalogues/demoRegistry/v2", "3", readCatalogue);
  });

  it("says which version is being chosen from", async () => {
    draw();

    expect(await screen.findByText("Choosing from 2026-06")).toBeInTheDocument();
  });

  it("says so when the catalogue has published nothing", () => {
    draw({ catalogueVersion: undefined });

    expect(screen.getByText(/published nothing to choose from/)).toBeInTheDocument();
    expect(useNode).not.toHaveBeenCalled();
  });

  it("reports a catalogue it could not read", async () => {
    nodeRead({ loadError: "The server could not be reached." });
    draw();

    expect(await screen.findByText("The server could not be reached.")).toBeInTheDocument();
  });

  it("saves each change as it is made, and says the request itself has changed", async () => {
    const { onChanged } = draw();
    await openPatients();

    await userEvent.click(screen.getByRole("checkbox", BIRTH_DATE));

    expect(saveDataSelection).toHaveBeenCalledWith(expect.any(Function), "/Submissions/one", "dataNeeded",
      [ BIRTH_DATE_KEY ]);
    expect(onChanged).toHaveBeenCalled();
    expect(await screen.findByText("Saved")).toBeInTheDocument();
  });

  it("saves in its turn among the submission's work, so a step that sends the request waits for it", async () => {
    const { queueSave, held } = heldQueue();
    draw({}, queueSave);
    await openPatients();

    await userEvent.click(screen.getByRole("checkbox", BIRTH_DATE));

    expect(saveDataSelection).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Saving")).toBeInTheDocument();
    await act(() => held[0]?.());
    expect(saveDataSelection).toHaveBeenCalledOnce();
  });

  it("sends the changes made while a save waits together, in that one save", async () => {
    const { queueSave, held } = heldQueue();
    draw({}, queueSave);
    await openPatients();

    await userEvent.click(screen.getByRole("checkbox", BIRTH_DATE));
    await userEvent.click(screen.getByRole("checkbox", GENDER));
    await act(() => held[0]?.());

    expect(queueSave).toHaveBeenCalledOnce();
    expect(saveDataSelection).toHaveBeenCalledWith(expect.any(Function), "/Submissions/one", "dataNeeded",
      [ BIRTH_DATE_KEY, GENDER_KEY ]);
  });

  it("keeps a tick made while a save is going out when the form is read back", async () => {
    saveDataSelection.mockReturnValue(new Promise(() => undefined));
    const { again } = draw();
    await openPatients();
    await userEvent.click(screen.getByRole("checkbox", BIRTH_DATE));
    await userEvent.click(screen.getByRole("checkbox", GENDER));

    // The form read back with only the first save stored, while the second still waits
    again({ fields: [ BIRTH_DATE_KEY ] });

    expect(screen.getByRole("checkbox", GENDER)).toBeChecked();
  });

  it("reports a refusal against the panel rather than losing it", async () => {
    saveDataSelection.mockRejectedValue(new Error("This request has been submitted"));
    const { onChanged } = draw();
    await openPatients();

    await userEvent.click(screen.getByRole("checkbox", BIRTH_DATE));

    expect(await screen.findByText("Not saved")).toBeInTheDocument();
    await userEvent.hover(screen.getByText("Not saved"));
    expect(await screen.findByRole("tooltip")).toHaveTextContent("This request has been submitted");
    expect(onChanged).not.toHaveBeenCalled();
  });

  it("keeps the selection when the form is read again unchanged", async () => {
    const { again } = draw({ fields: [ GENDER_KEY ] });
    await openPatients();

    // The same selection, in a new array, which is what a re-read of the form produces
    again({ fields: [ GENDER_KEY ] });

    expect(screen.getByRole("checkbox", GENDER)).toBeChecked();
  });

  it("adopts a selection that really did change underneath it", async () => {
    const { again } = draw({ fields: [] });
    await openPatients();
    expect(screen.getByRole("checkbox", BIRTH_DATE)).not.toBeChecked();

    again({ fields: [ BIRTH_DATE_KEY ] });

    expect(screen.getByRole("checkbox", BIRTH_DATE)).toBeChecked();
  });

  it("takes a projection that states no fields and no version label", async () => {
    draw({ fields: undefined, catalogueVersionLabel: undefined });

    expect(await screen.findByRole("button", { name: "records database" })).toBeInTheDocument();
    expect(screen.queryByText(/^Choosing from/)).not.toBeInTheDocument();
  });
});

describe("a request that can no longer be changed", () => {
  beforeEach(() => {
    nodeRead({ value: sampleCatalogue() });
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("still shows what was chosen", async () => {
    drawLocked({ fields: [ BIRTH_DATE_KEY ] });
    await openPatients();

    expect(screen.getByRole("checkbox", BIRTH_DATE)).toBeChecked();
  });

  it("offers no way to change it, and does not pretend otherwise", async () => {
    drawLocked({ fields: [ BIRTH_DATE_KEY ] });
    await openPatients();

    expect(screen.getByRole("checkbox", BIRTH_DATE)).toBeDisabled();
    expect(screen.queryByText("Saved")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Remove / })).not.toBeInTheDocument();
  });
});
