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

import { act, render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { CatalogueProvider } from "@iap/data-requirement/catalogue/CatalogueProvider";
import { SelectionProvider } from "@iap/data-requirement/catalogue/SelectionProvider";
import { useCatalogue } from "@iap/data-requirement/catalogue/useCatalogue";
import {
  useCatalogueFilter,
  type CatalogueFilterResult,
} from "@iap/data-requirement/catalogue/useCatalogueFilter";
import { useExpansion, type ExpansionApi } from "@iap/data-requirement/catalogue/useExpansion";
import {
  useGroupSelection,
  type GroupSelection,
} from "@iap/data-requirement/catalogue/useGroupSelection";
import { useSelection } from "@iap/data-requirement/catalogue/useSelection";

import { sampleCatalogue } from "./fixtures";

/** Mounts a hook on its own and hands back a getter for whatever it returns. */
function probe<T>(use: () => T, wrap: (children: React.ReactNode) => React.ReactNode = c => c) {
  let latest!: T;
  function Probe() {
    latest = use();
    return null;
  }
  const view = render(<>{wrap(<Probe />)}</>);
  return { latest: () => latest, rerender: () => { view.rerender(<>{wrap(<Probe />)}</>); } };
}

describe("narrowing the catalogue", () => {
  const filter = (query: string, excluded: string[] = []) =>
    probe<CatalogueFilterResult>(() => useCatalogueFilter(sampleCatalogue(), query,
      new Set(excluded))).latest();

  it("shows everything when nothing narrows it", () => {
    const result = filter("");

    expect(result.visible).toHaveLength(2);
    expect(result.shownFieldCount).toBe(5);
    expect(result.isFiltered).toBe(false);
  });

  it("keeps only the fields a search matches", () => {
    const result = filter("birth");

    expect(result.shownFieldCount).toBe(1);
    expect(result.visible[0].collections[0].fields[0].identifier).toBe("birthDate");
    expect(result.isFiltered).toBe(true);
  });

  // The identifier stays searchable even where nothing displays it
  it("matches a source's own name for a field, and a description", () => {
    expect(filter("dateGiven").shownFieldCount).toBe(1);
    expect(filter("DATEGIVEN").shownFieldCount).toBe(1);
  });

  // An empty group in a list of results reads as a result
  it("leaves out a collection nothing matched in", () => {
    const result = filter("birth");

    expect(result.visible).toHaveLength(1);
    expect(result.visible[0].collections).toHaveLength(1);
  });

  it("still says how many fields a collection really holds", () => {
    const result = filter("birth");

    expect(result.visible[0].collections[0].fields).toHaveLength(1);
    expect(result.visible[0].collections[0].totalFieldCount).toBe(2);
  });

  it("shows nothing at all when a search matches nothing", () => {
    const result = filter("nothing matches this");

    expect(result.visible).toEqual([]);
    expect(result.shownFieldCount).toBe(0);
  });

  it("drops a database somebody excluded", () => {
    const result = filter("", [ "registry" ]);

    expect(result.visible).toHaveLength(1);
    expect(result.includedDatabases.map(each => each.identifier)).toEqual([ "records" ]);
    expect(result.isFiltered).toBe(true);
  });

  it("counts only what is left after both are applied", () => {
    expect(filter("date", [ "registry" ]).shownFieldCount).toBe(1);
  });

  it("ignores the whitespace around a search", () => {
    expect(filter("   ").isFiltered).toBe(false);
  });
});

describe("which nodes of the tree are open", () => {
  it("starts with nothing open", () => {
    const { latest } = probe<ExpansionApi>(() => useExpansion());

    expect(latest().isOpen("records")).toBe(false);
  });

  it("starts with what it was told to open", () => {
    const { latest } = probe<ExpansionApi>(() => useExpansion([ "records" ]));

    expect(latest().isOpen("records")).toBe(true);
  });

  it("opens and shuts a node", () => {
    const { latest } = probe<ExpansionApi>(() => useExpansion());

    act(() => { latest().toggle("records"); });
    expect(latest().isOpen("records")).toBe(true);

    act(() => { latest().toggle("records"); });
    expect(latest().isOpen("records")).toBe(false);
  });

  it("opens everything, and shuts everything", () => {
    const { latest } = probe<ExpansionApi>(() => useExpansion());

    act(() => { latest().expandAll([ "records", "registry" ]); });
    expect(latest().isOpen("records")).toBe(true);
    expect(latest().isOpen("registry")).toBe(true);

    act(() => { latest().collapseAll(); });
    expect(latest().isOpen("records")).toBe(false);
  });

  // A catalogue arrives after the tree mounts, so what should start open is not known at that point
  it("takes what should be open when it arrives late", () => {
    let defaults: string[] = [];
    const { latest, rerender } = probe<ExpansionApi>(() => useExpansion(defaults));

    expect(latest().isOpen("records")).toBe(false);

    defaults = [ "records" ];
    act(() => { rerender(); });

    expect(latest().isOpen("records")).toBe(true);
  });

  // After that the open nodes are the reader's work, and a later render must not undo it
  it("does not reopen what the reader has shut", () => {
    let defaults = [ "records" ];
    const { latest, rerender } = probe<ExpansionApi>(() => useExpansion(defaults));

    act(() => { latest().toggle("records"); });
    expect(latest().isOpen("records")).toBe(false);

    defaults = [ "records", "registry" ];
    act(() => { rerender(); });

    expect(latest().isOpen("records")).toBe(false);
  });

  it("opens every database while a search is running", () => {
    const { latest } = probe<ExpansionApi>(() => useExpansion());

    expect(latest().isDatabaseOpen("records", true)).toBe(true);
    expect(latest().isDatabaseOpen("records", false)).toBe(false);
  });

  // Opening a collection with hundreds of matches would bury the other collections that matched too
  it("opens a collection during a search only while the matches are few", () => {
    const { latest } = probe<ExpansionApi>(() => useExpansion());

    expect(latest().isCollectionOpen("records/Patient", true, 5)).toBe(true);
    expect(latest().isCollectionOpen("records/Patient", true, 400)).toBe(false);
  });

  it("keeps a collection the reader opened open however many matched", () => {
    const { latest } = probe<ExpansionApi>(() => useExpansion([ "records/Patient" ]));

    expect(latest().isCollectionOpen("records/Patient", true, 400)).toBe(true);
  });
});

describe("a group's own view of the selection", () => {
  const group = (keys: string[], chosen: string[]) => {
    const onChange = vi.fn();
    const view = probe<GroupSelection>(() => useGroupSelection(keys),
      children => (
        <SelectionProvider value={chosen} onChange={onChange}>{children}</SelectionProvider>));
    return { latest: view.latest, onChange };
  };

  it("counts how many of its own fields are chosen", () => {
    expect(group([ "a", "b", "c" ], [ "a", "b" ]).latest().selectedCount).toBe(2);
  });

  it("shows nothing, some, or all of itself chosen", () => {
    expect(group([ "a", "b" ], []).latest().state).toBe("none");
    expect(group([ "a", "b" ], [ "a" ]).latest().state).toBe("some");
    expect(group([ "a", "b" ], [ "a", "b" ]).latest().state).toBe("all");
  });

  // Chosen fields outside the group are not the group's business
  it("counts only its own keys", () => {
    expect(group([ "a" ], [ "a", "z" ]).latest().selectedCount).toBe(1);
    expect(group([ "a" ], [ "a", "z" ]).latest().state).toBe("all");
  });

  it("takes the whole group in one change", () => {
    const { latest, onChange } = group([ "a", "b" ], []);

    act(() => { latest().setAll(true); });

    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith([ "a", "b" ]);
  });

  it("gives the whole group up in one change", () => {
    const { latest, onChange } = group([ "a", "b" ], [ "a", "b", "z" ]);

    act(() => { latest().setAll(false); });

    expect(onChange).toHaveBeenCalledWith([ "z" ]);
  });
});

describe("reaching a catalogue that is not there", () => {
  it("says so rather than drawing a tree with nothing in it", () => {
    // React reports the throw as well, and the suite should not read as though something broke
    const quiet = vi.spyOn(console, "error").mockImplementation(() => undefined);

    expect(() => probe(() => useCatalogue())).toThrow(/CatalogueProvider/);

    quiet.mockRestore();
  });

  it("says so when a selection is asked for outside a provider", () => {
    const quiet = vi.spyOn(console, "error").mockImplementation(() => undefined);

    expect(() => probe(() => useSelection())).toThrow(/SelectionProvider/);

    quiet.mockRestore();
  });
});

describe("what a catalogue provider hands down", () => {
  it("reads an absent catalogue as an empty one", () => {
    const { latest } = probe(() => useCatalogue(),
      children => <CatalogueProvider loading>{children}</CatalogueProvider>);

    expect(latest().catalogue.databases).toEqual([]);
    expect(latest().loading).toBe(true);
    expect(latest().error).toBeNull();
  });

  it("hands down the catalogue it was given, and the defaults it resolves", () => {
    const { latest } = probe(() => useCatalogue(),
      children => <CatalogueProvider catalogue={sampleCatalogue()}>{children}</CatalogueProvider>);

    expect(latest().catalogue.totalFields).toBe(5);
    expect(latest().display.tree.field.showPhi).toBe(true);
  });

  it("lays a deployment's overrides over the defaults", () => {
    const { latest } = probe(() => useCatalogue(),
      children => (
        <CatalogueProvider display={{ tree: { field: { showIdentifier: true } } }}>
          {children}
        </CatalogueProvider>));

    expect(latest().display.tree.field.showIdentifier).toBe(true);
    expect(latest().display.tree.field.showPhi).toBe(true);
  });

  it("hands down why the catalogue could not be read", () => {
    const { latest } = probe(() => useCatalogue(),
      children => <CatalogueProvider error="Nothing there">{children}</CatalogueProvider>);

    expect(latest().error).toBe("Nothing there");
  });
});
