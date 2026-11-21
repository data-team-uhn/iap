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

import { useState } from "react";

import { render, screen, waitForElementToBeRemoved, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import DataCatalogue, {
  type DataCatalogueProps,
} from "@iap/data-requirement/catalogue/DataCatalogue";
import { stubElementWidth } from "@iap/frontend-commons/resizeObserver.fixture";

import { catalogue, collection, database, sampleCatalogue } from "./catalogue.fixture";

// jsdom lays nothing out, so the catalogue stays unmeasured and takes the wide layout unless a width is stubbed
afterEach(() => {
  vi.unstubAllGlobals();
});

/** Holds the selection the way a host does, so a change is drawn as well as reported. */
function Host({ initial, onChange, ...props }: Partial<DataCatalogueProps> & {
  initial: readonly string[];
  onChange: (keys: string[]) => void;
}) {
  const [ value, setValue ] = useState<readonly string[]>(initial);
  return (
    <DataCatalogue
      catalogue={sampleCatalogue()}
      {...props}
      value={value}
      onChange={keys => {
        setValue(keys);
        onChange(keys);
      }}
    />);
}

function draw({ value = [], ...props }: Partial<Omit<DataCatalogueProps, "onChange">> = {}) {
  const onChange = vi.fn();
  render(<Host initial={value} onChange={onChange} {...props} />);
  return onChange;
}

describe("browsing and choosing, end to end", () => {
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

describe("keeping the keyboard where it was", () => {
  it("moves to the next field's remove control when one is given back", async () => {
    draw({ value: [ "records/Patient/birthDate", "records/Patient/gender" ] });

    await userEvent.click(screen.getByRole("button", { name: "Remove birthDate" }));

    expect(screen.getByRole("button", { name: "Remove gender" })).toHaveFocus();
  });

  it("moves to the panel's heading once nothing is left to give back", async () => {
    draw({ value: [ "records/Patient/birthDate" ] });

    await userEvent.click(screen.getByRole("button", { name: "Remove birthDate" }));

    expect(screen.getByText("Your selection")).toHaveFocus();
  });

  it("moves to the heading when everything is cleared", async () => {
    draw({ value: [ "records/Patient/birthDate" ] });

    await userEvent.click(screen.getByRole("button", { name: "Clear selection" }));

    expect(screen.getByText("Your selection")).toHaveFocus();
  });

  it("goes back to the search box when a search is cleared", async () => {
    draw();
    await userEvent.type(screen.getByLabelText("Filter fields"), "birth");

    await userEvent.click(screen.getByRole("button", { name: "Clear filter" }));

    expect(screen.getByLabelText("Filter fields")).toHaveFocus();
  });
});

describe("a selection somebody may only look at", () => {
  it("lists what was chosen, with no way to give any of it back", () => {
    draw({ value: [ "records/Patient/birthDate" ], readOnly: true });

    const panel = screen.getByRole("complementary", { name: "Your selection" });
    expect(within(panel).getByText("1 field from 1 collection")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Remove birthDate" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Remove all" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Clear selection" })).not.toBeInTheDocument();
  });

  it("does not invite ticking anything while nothing is chosen", () => {
    draw({ readOnly: true });

    expect(screen.getByText("Nothing selected yet")).toBeInTheDocument();
    expect(screen.queryByText(/Tick fields in the tree/)).not.toBeInTheDocument();
  });
});

describe("a choice this version no longer offers", () => {
  it("is counted and listed on its own, so the panel adds up", () => {
    draw({ value: [ "records/Patient/birthDate", "records/Patient/retired" ] });

    expect(screen.getByText("No longer offered")).toBeInTheDocument();
    expect(screen.getByText("records/Patient/retired")).toBeInTheDocument();
    expect(screen.getByText("2 fields from 1 collection")).toBeInTheDocument();
  });

  it("can be given back on its own", async () => {
    const onChange = draw({ value: [ "records/Patient/birthDate", "records/Patient/retired" ] });

    await userEvent.click(screen.getByRole("button", { name: "Remove records/Patient/retired" }));

    expect(onChange).toHaveBeenCalledWith([ "records/Patient/birthDate" ]);
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

  it("shows a source's own name in the panel where a deployment asked for it", () => {
    const curated = catalogue([ database("records", [
      collection("records", "Patient", [ "birthDate" ], {
        label: "Date of birth", labelIsFallback: false }) ]) ]);
    draw({ catalogue: curated, value: [ "records/Patient/birthDate" ],
      display: { tree: { field: { showIdentifier: true } } } });

    const panel = screen.getByRole("complementary", { name: "Your selection" });
    expect(within(panel).getByText("birthDate")).toBeInTheDocument();
  });

  it("still warns where a deployment took the marker off the rows", () => {
    draw({ catalogue: identifying(), value: [ "records/Patient/birthDate" ],
      display: { tree: { field: { showPhi: false } } } });

    expect(screen.getByText(/1 of 1 field may need approval/)).toBeInTheDocument();
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

    // The whole catalogue is on offer again, and the collections the search opened are collapsed
    expect(screen.getByRole("button", { name: "Consent collection" })).toBeInTheDocument();
    expect(screen.getByLabelText("Filter fields")).toHaveValue("");
  });

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
    await userEvent.click(screen.getByRole("menuitemcheckbox", { name: /registry/ }));
    await userEvent.keyboard("{Escape}");

    expect(screen.getByRole("button", { name: /Databases \(1 of 2\)/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Consent collection" })).not.toBeInTheDocument();
  });

  it("says which databases are included", async () => {
    draw();
    await userEvent.click(screen.getByRole("button", { name: /Databases/ }));

    await userEvent.click(screen.getByRole("menuitemcheckbox", { name: /registry/ }));

    expect(screen.getByRole("menuitemcheckbox", { name: /registry/ })).toHaveAttribute("aria-checked", "false");
    expect(screen.getByRole("menuitemcheckbox", { name: /records/ })).toHaveAttribute("aria-checked", "true");
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
    await userEvent.click(screen.getByRole("menuitemcheckbox", { name: /registry/ }));

    await userEvent.click(screen.getByRole("menuitemcheckbox", { name: /registry/ }));
    await userEvent.keyboard("{Escape}");

    expect(screen.getByRole("button", { name: /Databases \(2 of 2\)/ })).toBeInTheDocument();
  });

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
  it("puts the host's actions above the tree", () => {
    draw({ actions: <button type="button">Save selection</button> });

    expect(screen.getByRole("button", { name: "Save selection" })).toBeInTheDocument();
  });

  it("shows the host's own notices among its own", () => {
    draw({ notices: <div>Carried over from an earlier request</div> });

    expect(screen.getByText("Carried over from an earlier request")).toBeInTheDocument();
  });

  it("says the catalogue is still coming while there is none and no error", () => {
    draw({ catalogue: undefined });

    expect(screen.getByLabelText("Loading the catalogue")).toBeInTheDocument();
  });

  it("says why the catalogue could not be read", () => {
    draw({ catalogue: undefined, error: "That version is gone" });

    expect(screen.getByText("That version is gone")).toBeInTheDocument();
    expect(screen.queryByLabelText("Filter fields")).not.toBeInTheDocument();
  });
});

describe("where there is no room beside the tree", () => {
  it("stands the panel down to a summary bar", () => {
    stubElementWidth(400);
    draw({ value: [ "records/Patient/birthDate" ] });

    expect(screen.getByText("field selected")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Review selection" })).toBeEnabled();
    expect(screen.queryByText("Nothing selected yet")).not.toBeInTheDocument();
  });

  it("opens the panel behind it", async () => {
    stubElementWidth(400);
    draw({ value: [ "records/Patient/birthDate" ] });

    await userEvent.click(screen.getByRole("button", { name: "Review selection" }));

    expect(screen.getByText("1 field from 1 collection")).toBeInTheDocument();
  });

  it("offers nothing to review while nothing is chosen", () => {
    stubElementWidth(400);
    draw();

    expect(screen.getByRole("button", { name: "Review selection" })).toHaveAttribute("aria-disabled", "true");
  });

  it("shuts the panel when it is dismissed rather than closed", async () => {
    stubElementWidth(400);
    draw({ value: [ "records/Patient/birthDate" ] });
    await userEvent.click(screen.getByRole("button", { name: "Review selection" }));

    await userEvent.keyboard("{Escape}");

    await waitForElementToBeRemoved(() => screen.queryByText("1 field from 1 collection"));
  });

  it("shuts the panel again", async () => {
    stubElementWidth(400);
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

    expect(screen.getByRole("button", { name: "Expand selection panel, 1 field selected" })).toBeInTheDocument();
    expect(screen.queryByText("1 field from 1 collection")).not.toBeInTheDocument();
  });

  it("brings it back", async () => {
    draw();
    await userEvent.click(screen.getByRole("button", { name: "Collapse panel" }));

    await userEvent.click(screen.getByRole("button", { name: /Expand selection panel/ }));

    expect(screen.getByText("Nothing selected yet")).toBeInTheDocument();
  });

  it("keeps the keyboard on whichever control replaced the one pressed", async () => {
    draw();

    await userEvent.click(screen.getByRole("button", { name: "Collapse panel" }));
    expect(screen.getByRole("button", { name: /Expand selection panel/ })).toHaveFocus();

    await userEvent.click(screen.getByRole("button", { name: /Expand selection panel/ }));
    expect(screen.getByRole("button", { name: "Collapse panel" })).toHaveFocus();
  });
});

describe("laying out by the room the catalogue is given, not the window", () => {
  it("puts the panel beside the tree where both fit", () => {
    stubElementWidth(784);
    draw();

    expect(screen.getByRole("complementary", { name: "Your selection" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Review selection" })).not.toBeInTheDocument();
  });

  it("stands the panel down a pixel short of that", () => {
    stubElementWidth(783);
    draw();

    expect(screen.getByRole("button", { name: "Review selection" })).toBeInTheDocument();
  });
});
