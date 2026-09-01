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
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { CatalogueProvider } from "@iap/data-requirement/catalogue/CatalogueProvider";
import CatalogueTree from "@iap/data-requirement/catalogue/components/CatalogueTree";
import type { DisplayOverride } from "@iap/data-requirement/catalogue/display";
import { SelectionProvider } from "@iap/data-requirement/catalogue/SelectionProvider";
import type { Catalogue } from "@iap/data-requirement/catalogue/types";
import { useCatalogueFilter } from "@iap/data-requirement/catalogue/useCatalogueFilter";
import { useExpansion } from "@iap/data-requirement/catalogue/useExpansion";

import { catalogue, collection, database, sampleCatalogue } from "./fixtures";

interface HarnessProps {
  data?: Catalogue;
  query?: string;
  excluded?: string[];
  chosen?: string[];
  display?: DisplayOverride;
  onChange?: (keys: string[]) => void;
  openByDefault?: string[];
}

/** The tree wired the way a page wires it: a filter, an expansion, and the two providers. */
function Harness({ data, query = "", excluded = [], chosen = [], display, onChange = () => undefined,
  openByDefault = [] }: HarnessProps) {
  const shown = data ?? sampleCatalogue();
  const filter = useCatalogueFilter(shown, query, new Set(excluded));
  const expansion = useExpansion(openByDefault);
  return (
    <CatalogueProvider catalogue={shown} display={display}>
      <SelectionProvider value={chosen} onChange={onChange}>
        <CatalogueTree
          visible={filter.visible}
          expansion={expansion}
          query={query}
          // "Nobody has included one", not "there are none to include": offering to include them
          // all when the catalogue holds none would be a way out of nowhere
          noDatabasesIncluded={shown.databases.length > 0 && filter.includedDatabases.length === 0}
          onClearQuery={() => undefined}
          onIncludeAllDatabases={() => undefined}
        />
      </SelectionProvider>
    </CatalogueProvider>
  );
}

const draw = (props: HarnessProps = {}) => render(<Harness {...props} />);

describe("browsing what there is to choose from", () => {
  it("lists the databases, shut", () => {
    draw();

    expect(screen.getByRole("button", { name: "records database" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "registry database" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Patient collection" })).not.toBeInTheDocument();
  });

  it("says what a database holds before it is opened", () => {
    draw();

    expect(screen.getByText("3 fields · 2 collections")).toBeInTheDocument();
  });

  it("says it in the singular where there is one of something", () => {
    draw({ data: catalogue([ database("records", [ collection("records", "Patient", [ "birthDate" ]) ]) ]) });

    expect(screen.getByText("1 field · 1 collection")).toBeInTheDocument();
  });

  it("opens a database to its collections", async () => {
    draw();

    await userEvent.click(screen.getByRole("button", { name: "records database" }));

    expect(screen.getByRole("button", { name: "Patient collection" })).toBeInTheDocument();
    expect(screen.queryByLabelText("birthDate")).not.toBeInTheDocument();
  });

  it("opens a collection to its fields", async () => {
    draw({ openByDefault: [ "records" ] });

    await userEvent.click(screen.getByRole("button", { name: "Patient collection" }));

    expect(screen.getByLabelText("birthDate")).toBeInTheDocument();
    expect(screen.getByLabelText("gender")).toBeInTheDocument();
  });

  // A row is not a button element, because it holds a checkbox and a caret
  it("opens a node from the keyboard", async () => {
    draw();
    const row = screen.getByRole("button", { name: "records database" });

    row.focus();
    await userEvent.keyboard("{Enter}");
    expect(screen.getByRole("button", { name: "Patient collection" })).toBeInTheDocument();

    await userEvent.keyboard(" ");
    expect(screen.queryByRole("button", { name: "Patient collection" })).not.toBeInTheDocument();
  });

  it("ignores a key that is not one that presses a button", async () => {
    draw();
    screen.getByRole("button", { name: "records database" }).focus();

    await userEvent.keyboard("a");

    expect(screen.queryByRole("button", { name: "Patient collection" })).not.toBeInTheDocument();
  });

  it("shows what a database is for, where the catalogue says", () => {
    const described = catalogue([ {
      ...database("records", [ collection("records", "Patient", [ "birthDate" ]) ]),
      description: "Everything the clinic recorded",
    } ]);
    draw({ data: described });

    expect(screen.getByText("Everything the clinic recorded")).toBeInTheDocument();
  });

  it("says whether a node is open", async () => {
    draw();
    const row = screen.getByRole("button", { name: "records database" });

    expect(row).toHaveAttribute("aria-expanded", "false");

    await userEvent.click(row);

    expect(screen.getByRole("button", { name: "records database" }))
      .toHaveAttribute("aria-expanded", "true");
  });
});

describe("choosing fields", () => {
  it("chooses one", async () => {
    const onChange = vi.fn();
    draw({ openByDefault: [ "records", "records/Patient" ], onChange });

    await userEvent.click(screen.getByLabelText("birthDate"));

    expect(onChange).toHaveBeenCalledWith([ "records/Patient/birthDate" ]);
  });

  // The whole row is a click target, though the checkbox is the control assistive technology sees
  it("chooses one by clicking anywhere along its row", async () => {
    const onChange = vi.fn();
    draw({ openByDefault: [ "records", "records/Patient" ], onChange });

    await userEvent.click(screen.getByText("birthDate"));

    expect(onChange).toHaveBeenCalledWith([ "records/Patient/birthDate" ]);
  });

  it("gives one up", async () => {
    const onChange = vi.fn();
    draw({ openByDefault: [ "records", "records/Patient" ],
      chosen: [ "records/Patient/birthDate" ], onChange });

    await userEvent.click(screen.getByLabelText("birthDate"));

    expect(onChange).toHaveBeenCalledWith([]);
  });

  it("takes a whole collection at once", async () => {
    const onChange = vi.fn();
    draw({ openByDefault: [ "records" ], onChange });

    await userEvent.click(screen.getByLabelText("Select all fields in Patient"));

    expect(onChange).toHaveBeenCalledWith([ "records/Patient/birthDate", "records/Patient/gender" ]);
  });

  it("takes a whole database at once", async () => {
    const onChange = vi.fn();
    draw({ onChange });

    await userEvent.click(screen.getByLabelText("Select all fields in records"));

    expect(onChange).toHaveBeenCalledWith([
      "records/Patient/birthDate", "records/Patient/gender", "records/Encounter/period" ]);
  });

  // Half-chosen has to read as its own state, or a reader who picked two of thirty is told nothing
  it("shows a part-chosen group as neither on nor off", () => {
    draw({ openByDefault: [ "records" ], chosen: [ "records/Patient/birthDate" ] });

    expect(screen.getByLabelText("Select all fields in Patient")).toBePartiallyChecked();
    expect(screen.getByLabelText("Select all fields in records")).toBePartiallyChecked();
  });

  it("shows a wholly chosen group as on", () => {
    draw({ openByDefault: [ "records" ],
      chosen: [ "records/Patient/birthDate", "records/Patient/gender" ] });

    expect(screen.getByLabelText("Select all fields in Patient")).toBeChecked();
  });

  it("gives a whole group up when it is already wholly chosen", async () => {
    const onChange = vi.fn();
    draw({ openByDefault: [ "records" ],
      chosen: [ "records/Patient/birthDate", "records/Patient/gender" ], onChange });

    await userEvent.click(screen.getByLabelText("Select all fields in Patient"));

    expect(onChange).toHaveBeenCalledWith([]);
  });

  it("counts what has been chosen out of a collection", () => {
    draw({ openByDefault: [ "records" ], chosen: [ "records/Patient/birthDate" ] });

    expect(screen.getByText(/1 of 2/)).toBeInTheDocument();
  });

  it("counts what has been chosen out of a database", () => {
    draw({ chosen: [ "records/Patient/birthDate" ] });

    expect(screen.getByText("1 selected")).toBeInTheDocument();
  });
});

describe("searching", () => {
  it("keeps only what matched, and opens what holds it", () => {
    draw({ query: "birth" });

    expect(screen.getByLabelText("birthDate")).toBeInTheDocument();
    expect(screen.queryByLabelText("gender")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "registry database" })).not.toBeInTheDocument();
  });

  it("says how much of a collection matched", () => {
    draw({ query: "birth" });

    expect(screen.getByText("1 of 2")).toBeInTheDocument();
  });

  it("says how much of a database matched", () => {
    draw({ query: "birth" });

    expect(screen.getByText("1 of 3 fields · 1 of 2 collections")).toBeInTheDocument();
  });

  it("says so when nothing matched", () => {
    draw({ query: "nothing at all" });

    expect(screen.getByText("No fields match “nothing at all”")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Show all fields" })).toBeInTheDocument();
  });

  // Excluding every database is a different nothing from a search that found nothing
  it("says so when every database has been excluded", () => {
    draw({ excluded: [ "records", "registry" ] });

    expect(screen.getByText("No databases included")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Include all databases" })).toBeInTheDocument();
  });

  it("offers no way out of an empty catalogue, because there is none", () => {
    draw({ data: catalogue([]) });

    expect(screen.getByText("No fields to show")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Show all fields" })).not.toBeInTheDocument();
  });
});

describe("how much of a field is shown", () => {
  const shown = () => catalogue([
    database("records", [
      collection("records", "Patient", [ "birthDate" ], {
        label: "Date of birth",
        labelIsFallback: false,
        description: "The day they were born",
        cardinality: "1..1",
        dataType: "date",
        phi: true,
        example: "1970-01-01",
      }),
    ]),
  ]);

  const open = { openByDefault: [ "records", "records/Patient" ], data: shown() };

  it("shows what the defaults ask for and no more", () => {
    draw(open);

    expect(screen.getByText("Date of birth")).toBeInTheDocument();
    expect(screen.getByText("The day they were born")).toBeInTheDocument();
    expect(screen.getByText("PHI")).toBeInTheDocument();
    expect(screen.getByText("e.g. 1970-01-01")).toBeInTheDocument();
    // Off by default: notation and identifiers are for whoever engineers the data
    expect(screen.queryByText("value: always")).not.toBeInTheDocument();
    expect(screen.queryByText("birthDate")).not.toBeInTheDocument();
  });

  it("shows the notation in words where a deployment asked for it", () => {
    draw({ ...open, display: { tree: { field: { showCardinality: true } } } });

    expect(screen.getByText("value: always")).toBeInTheDocument();
  });

  // Showing raw notation would be worse than showing nothing, but there is nothing to explain either
  it("shows a notation it has no words for exactly as the catalogue wrote it", () => {
    const odd = catalogue([ database("records", [
      collection("records", "Patient", [ "birthDate" ], { cardinality: "2..7" }) ]) ]);
    draw({ data: odd, openByDefault: [ "records", "records/Patient" ],
      display: { tree: { field: { showCardinality: true } } } });

    expect(screen.getByText("2..7")).toBeInTheDocument();
  });

  it("says nothing at all where the catalogue gave no notation", () => {
    const silent = catalogue([ database("records", [
      collection("records", "Patient", [ "birthDate" ], { cardinality: "" }) ]) ]);
    draw({ data: silent, openByDefault: [ "records", "records/Patient" ],
      display: { tree: { field: { showCardinality: true } } } });

    expect(screen.queryByText(/^value:/)).not.toBeInTheDocument();
  });

  it("shows the source's own name where a deployment asked for it", () => {
    draw({ ...open, display: { tree: { field: { showIdentifier: true, showType: true } } } });

    expect(screen.getByText("birthDate")).toBeInTheDocument();
    expect(screen.getByText("date")).toBeInTheDocument();
  });

  // Repeating it is pointless when the label was derived from it. Counted rather than looked for,
  // because the label itself reads `birthDate` in that case — one of them is the label, and a
  // second would be the identifier printed beside it
  it("leaves the identifier off where the label is only a humanised copy of it", () => {
    draw({ openByDefault: [ "records", "records/Patient" ],
      display: { tree: { field: { showIdentifier: true } } } });

    expect(screen.getAllByText("birthDate")).toHaveLength(1);
  });

  it("keeps quiet about identifiability where the catalogue said nothing", () => {
    draw({ openByDefault: [ "records", "records/Patient" ] });

    expect(screen.queryByText("PHI")).not.toBeInTheDocument();
  });

  it("shows a collection's own name where a deployment asked for it", () => {
    draw({ openByDefault: [ "records" ] });
    // The label alone, since nothing curated one and it reads as the identifier
    expect(screen.getAllByText("Patient")).toHaveLength(1);

    draw({ openByDefault: [ "records" ],
      display: { tree: { collection: { showIdentifier: true } } } });
    // The label, and now the source's own name beside it
    expect(screen.getAllByText("Patient")).toHaveLength(3);
  });
});
