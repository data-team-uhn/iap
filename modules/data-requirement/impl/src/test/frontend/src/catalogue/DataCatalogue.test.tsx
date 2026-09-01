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

import { render, screen, waitForElementToBeRemoved, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import DataCatalogue, {
  type DataCatalogueProps,
} from "@iap/data-requirement/catalogue/DataCatalogue";

import { catalogue, collection, database, sampleCatalogue } from "./fixtures";

// jsdom implements no media queries, so MUI's breakpoint reads false without a stand-in and the
// wide layout is what renders. Neither layout can be checked for *fitting* here — only for which
// one is drawn; that it leaves room for the field list needs a browser.
const stubMatchMedia = (compact: boolean) => vi.stubGlobal("matchMedia", (query: string) => ({
  matches: compact,
  media: query,
  onchange: null,
  addListener: () => { /* deprecated, unused */ },
  removeListener: () => { /* deprecated, unused */ },
  addEventListener: () => { /* no live changes in these tests */ },
  removeEventListener: () => { /* no live changes in these tests */ },
  dispatchEvent: () => false,
}));

function draw(props: Partial<DataCatalogueProps> = {}) {
  const onChange = props.onChange ?? vi.fn();
  render(
    <DataCatalogue
      catalogue={props.catalogue ?? sampleCatalogue()}
      value={props.value ?? []}
      onChange={onChange}
      {...props}
    />);
  return onChange;
}

describe("browsing and choosing, end to end", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("opens with every database expanded, so nothing starts hidden", () => {
    draw();

    expect(screen.getByRole("button", { name: "Patient collection" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Consent collection" })).toBeInTheDocument();
  });

  it("says nothing is chosen yet", () => {
    draw();

    expect(screen.getByText("Nothing selected yet")).toBeInTheDocument();
    expect(screen.getByText("no fields selected")).toBeInTheDocument();
  });

  it("lists what has been chosen, under the collection it came from", () => {
    draw({ value: [ "records/Patient/birthDate" ] });

    const panel = screen.getByRole("complementary", { name: "Your selection" });
    expect(within(panel).getByText("1 field from 1 collection")).toBeInTheDocument();
    expect(within(panel).getByText("Patient")).toBeInTheDocument();
  });

  it("gives a chosen field back from the panel", async () => {
    const onChange = draw({ value: [ "records/Patient/birthDate", "records/Patient/gender" ] });

    await userEvent.click(screen.getByRole("button", { name: "Remove birthDate" }));

    expect(onChange).toHaveBeenCalledWith([ "records/Patient/gender" ]);
  });

  it("gives a whole collection back from the panel", async () => {
    const onChange = draw({ value: [ "records/Patient/birthDate", "records/Encounter/period" ] });

    await userEvent.click(screen.getAllByRole("button", { name: "Remove all" })[0]);

    expect(onChange).toHaveBeenCalledWith([ "records/Encounter/period" ]);
  });

  it("gives everything back at once", async () => {
    const onChange = draw({ value: [ "records/Patient/birthDate", "records/Encounter/period" ] });

    await userEvent.click(screen.getByRole("button", { name: "Clear selection" }));

    expect(onChange).toHaveBeenCalledWith([]);
  });

  it("offers no way to clear a selection that is already empty", () => {
    draw();

    expect(screen.queryByRole("button", { name: "Clear selection" })).not.toBeInTheDocument();
  });
});

describe("saying something about identifiability", () => {
  const identifying = () => catalogue([ database("records", [
    collection("records", "Patient", [ "birthDate", "gender" ], { phi: true }) ]) ]);

  it("says how many of the chosen fields may need approval", () => {
    draw({ catalogue: identifying(), value: [ "records/Patient/birthDate" ] });

    expect(screen.getByText(/1 of 1 field may need approval/)).toBeInTheDocument();
  });

  it("says nothing while nothing identifying has been chosen", () => {
    draw({ value: [ "records/Patient/birthDate" ] });

    expect(screen.queryByText(/may need approval/)).not.toBeInTheDocument();
  });

  // Turning the flag off has to hide it in the panel as well as the tree, or a selection still
  // marking fields while the tree has stopped would read as the tree being clean
  it("shows a source's own name in the panel where a deployment asked for it", () => {
    const curated = catalogue([ database("records", [
      collection("records", "Patient", [ "birthDate" ], {
        label: "Date of birth", labelIsFallback: false }) ]) ]);
    draw({ catalogue: curated, value: [ "records/Patient/birthDate" ],
      display: { tree: { field: { showIdentifier: true } } } });

    const panel = screen.getByRole("complementary", { name: "Your selection" });
    expect(within(panel).getByText("birthDate")).toBeInTheDocument();
  });

  it("says nothing where a deployment turned the marker off", () => {
    draw({ catalogue: identifying(), value: [ "records/Patient/birthDate" ],
      display: { tree: { field: { showPhi: false } } } });

    expect(screen.queryByText(/may need approval/)).not.toBeInTheDocument();
    expect(screen.queryByText("PHI")).not.toBeInTheDocument();
  });
});

describe("narrowing what is on offer", () => {
  it("searches", async () => {
    draw();

    await userEvent.type(screen.getByLabelText("Filter fields"), "birth");

    expect(screen.getByLabelText("birthDate")).toBeInTheDocument();
    expect(screen.queryByLabelText("gender")).not.toBeInTheDocument();
  });

  it("clears a search from the box", async () => {
    draw();
    await userEvent.type(screen.getByLabelText("Filter fields"), "birth");

    await userEvent.click(screen.getByRole("button", { name: "Clear filter" }));

    // The whole catalogue is on offer again. The collections are shut, because a search opened them
    // and nothing else did
    expect(screen.getByRole("button", { name: "Consent collection" })).toBeInTheDocument();
    expect(screen.getByLabelText("Filter fields")).toHaveValue("");
  });

  // The way out offered where the search found nothing, which is a different control from the
  // toolbar's clear button and reached at the moment the toolbar is easiest to overlook
  it("clears a search that found nothing, from where it says so", async () => {
    draw();
    await userEvent.type(screen.getByLabelText("Filter fields"), "nothing at all");
    expect(screen.getByText("No fields match “nothing at all”")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Show all fields" }));

    expect(screen.getByRole("button", { name: "Patient collection" })).toBeInTheDocument();
  });

  it("shuts and reopens the whole tree", async () => {
    draw();

    await userEvent.click(screen.getByRole("button", { name: "Collapse all" }));
    expect(screen.queryByRole("button", { name: "Patient collection" })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Expand all" }));
    expect(screen.getByRole("button", { name: "Patient collection" })).toBeInTheDocument();
  });

  it("excludes a database, and says how many are left", async () => {
    draw();

    await userEvent.click(screen.getByRole("button", { name: /Databases \(2 of 2\)/ }));
    await userEvent.click(screen.getByRole("menuitem", { name: /registry/ }));
    await userEvent.keyboard("{Escape}");

    expect(screen.getByRole("button", { name: /Databases \(1 of 2\)/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Consent collection" })).not.toBeInTheDocument();
  });

  it("excludes them all, then takes them back", async () => {
    draw();

    await userEvent.click(screen.getByRole("button", { name: /Databases/ }));
    await userEvent.click(screen.getByRole("menuitem", { name: "Exclude all" }));
    expect(screen.getByText("No databases included")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Include all databases" }));
    expect(screen.getByRole("button", { name: "Patient collection" })).toBeInTheDocument();
  });


  it("takes a database back by ticking it again", async () => {
    draw();
    await userEvent.click(screen.getByRole("button", { name: /Databases/ }));
    await userEvent.click(screen.getByRole("menuitem", { name: /registry/ }));

    await userEvent.click(screen.getByRole("menuitem", { name: /registry/ }));
    await userEvent.keyboard("{Escape}");

    expect(screen.getByRole("button", { name: /Databases \(2 of 2\)/ })).toBeInTheDocument();
  });

  // The tree's own way back, which is a different control from the menu's
  it("takes them all back from the tree's empty state", async () => {
    draw();
    await userEvent.click(screen.getByRole("button", { name: /Databases/ }));
    await userEvent.click(screen.getByRole("menuitem", { name: "Exclude all" }));

    await userEvent.click(screen.getByRole("button", { name: "Include all databases" }));

    expect(screen.getByRole("button", { name: /Databases \(2 of 2\)/ })).toBeInTheDocument();
  });

  it("takes them all back from the menu too", async () => {
    draw();
    await userEvent.click(screen.getByRole("button", { name: /Databases/ }));
    await userEvent.click(screen.getByRole("menuitem", { name: "Exclude all" }));

    await userEvent.click(screen.getByRole("button", { name: /Databases/ }));
    await userEvent.click(screen.getByRole("menuitem", { name: "Include all" }));

    expect(screen.getByRole("button", { name: "Patient collection" })).toBeInTheDocument();
  });
});

describe("what surrounds the catalogue is the host's", () => {
  it("puts the host's actions at the foot of the panel", () => {
    draw({ actions: <button type="button">Save selection</button> });

    expect(screen.getByRole("button", { name: "Save selection" })).toBeInTheDocument();
  });

  it("shows the host's own notices among its own", () => {
    draw({ notices: <div>Carried over from an earlier request</div> });

    expect(screen.getByText("Carried over from an earlier request")).toBeInTheDocument();
  });

  it("says the catalogue is still coming", () => {
    draw({ catalogue: undefined, loading: true });

    expect(screen.getByLabelText("Loading the catalogue")).toBeInTheDocument();
  });

  it("says why the catalogue could not be read", () => {
    draw({ catalogue: undefined, error: "That version is gone" });

    expect(screen.getByText("That version is gone")).toBeInTheDocument();
    expect(screen.queryByLabelText("Filter fields")).not.toBeInTheDocument();
  });
});

describe("where there is no room beside the tree", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("stands the panel down to a summary bar", () => {
    stubMatchMedia(true);
    draw({ value: [ "records/Patient/birthDate" ] });

    expect(screen.getByText("field selected")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Review selection" })).toBeEnabled();
    expect(screen.queryByText("Nothing selected yet")).not.toBeInTheDocument();
  });

  it("opens the panel behind it", async () => {
    stubMatchMedia(true);
    draw({ value: [ "records/Patient/birthDate" ] });

    await userEvent.click(screen.getByRole("button", { name: "Review selection" }));

    expect(screen.getByText("1 field from 1 collection")).toBeInTheDocument();
  });

  // Nothing to review is nothing to open
  it("offers nothing to review while nothing is chosen", () => {
    stubMatchMedia(true);
    draw();

    expect(screen.getByRole("button", { name: "Review selection" })).toBeDisabled();
  });

  it("shuts the panel when it is dismissed rather than closed", async () => {
    stubMatchMedia(true);
    draw({ value: [ "records/Patient/birthDate" ] });
    await userEvent.click(screen.getByRole("button", { name: "Review selection" }));

    await userEvent.keyboard("{Escape}");

    await waitForElementToBeRemoved(() => screen.queryByText("1 field from 1 collection"));
  });

  it("shuts the panel again", async () => {
    stubMatchMedia(true);
    draw({ value: [ "records/Patient/birthDate" ] });
    await userEvent.click(screen.getByRole("button", { name: "Review selection" }));

    await userEvent.click(screen.getByRole("button", { name: "Close selection" }));

    // The drawer animates out, so it is still in the document for a moment after the click
    await waitForElementToBeRemoved(() => screen.queryByText("1 field from 1 collection"));
  });
});

describe("giving the tree the whole width", () => {
  it("collapses the panel to a rail carrying the count", async () => {
    draw({ value: [ "records/Patient/birthDate" ] });

    await userEvent.click(screen.getByRole("button", { name: "Collapse panel" }));

    expect(screen.getByRole("button", { name: "Expand selection panel" })).toBeInTheDocument();
    expect(screen.queryByText("Nothing selected yet")).not.toBeInTheDocument();
  });

  it("brings it back", async () => {
    draw();
    await userEvent.click(screen.getByRole("button", { name: "Collapse panel" }));

    await userEvent.click(screen.getByRole("button", { name: "Expand selection panel" }));

    expect(screen.getByText("Nothing selected yet")).toBeInTheDocument();
  });
});
