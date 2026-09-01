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

import type { ReactNode } from "react";

import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { CatalogueProvider } from "@iap/data-requirement/catalogue/CatalogueProvider";
import { SelectionProvider } from "@iap/data-requirement/catalogue/SelectionProvider";
import { useCatalogue } from "@iap/data-requirement/catalogue/useCatalogue";
import { useCatalogueFilter } from "@iap/data-requirement/catalogue/useCatalogueFilter";
import { useExpansion } from "@iap/data-requirement/catalogue/useExpansion";
import { useGroupSelection } from "@iap/data-requirement/catalogue/useGroupSelection";
import { useSelection } from "@iap/data-requirement/catalogue/useSelection";

import { catalogue, collection, database, sampleCatalogue } from "./catalogue.fixture";

describe("narrowing the catalogue", () => {
  const filter = (query: string, excluded: string[] = [], from = sampleCatalogue()) =>
    renderHook(() => useCatalogueFilter(from, query, new Set(excluded))).result.current;
  const shownFields = (query: string, excluded: string[] = []) =>
    filter(query, excluded).visible.reduce((total, entry) => total + entry.shownFieldCount, 0);

  it("shows everything when nothing narrows it", () => {
    const result = filter("");

    expect(result.visible).toHaveLength(2);
    expect(shownFields("")).toBe(5);
    expect(result.searching).toBe(false);
  });

  it("keeps only the fields a search matches", () => {
    const result = filter("birth");

    expect(shownFields("birth")).toBe(1);
    expect(result.visible[0].collections[0].fields[0].identifier).toBe("birthDate");
    expect(result.searching).toBe(true);
  });

  it("matches a source's own name for a field, in any case", () => {
    expect(shownFields("dateGiven")).toBe(1);
    expect(shownFields("DATEGIVEN")).toBe(1);
  });

  it("matches a field's description", () => {
    const described = catalogue([ database("records", [
      collection("records", "Patient", [ "dob" ], { description: "The day the person was born" }),
    ]) ]);

    expect(filter("was born", [], described).visible).toHaveLength(1);
  });

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

  it("keeps an empty collection while nothing is searched, so the counts match what is drawn", () => {
    const withEmpty = catalogue([ database("records", [
      collection("records", "Patient", [ "birthDate" ]),
      collection("records", "Empty", []),
    ]) ]);

    expect(filter("", [], withEmpty).visible[0].collections).toHaveLength(2);
    expect(filter("birth", [], withEmpty).visible[0].collections).toHaveLength(1);
  });

  it("keeps a database with nothing in it while nothing is searched, as the Databases menu does", () => {
    const hollow = catalogue([
      database("records", [ collection("records", "Empty", []) ]),
      database("registry", []),
    ]);

    expect(filter("", [], hollow).visible).toHaveLength(2);
    expect(filter("birth", [], hollow).visible).toEqual([]);
  });

  it("shows nothing at all when a search matches nothing", () => {
    expect(filter("nothing matches this").visible).toEqual([]);
  });

  it("drops a database somebody excluded", () => {
    const result = filter("", [ "registry" ]);

    expect(result.visible.map(entry => entry.database.identifier)).toEqual([ "records" ]);
    expect(result.noDatabasesIncluded).toBe(false);
  });

  it("says when every database was excluded", () => {
    expect(filter("", [ "records", "registry" ]).noDatabasesIncluded).toBe(true);
    expect(filter("", [], catalogue([])).noDatabasesIncluded).toBe(false);
  });

  it("counts only what is left after both are applied", () => {
    expect(shownFields("date", [ "registry" ])).toBe(1);
  });

  it("ignores the whitespace around a search", () => {
    expect(filter("   ").searching).toBe(false);
  });
});

describe("which nodes of the tree are open", () => {
  const expansion = (defaults: string[] = [], searching = false) =>
    renderHook(({ isSearching }) => useExpansion(defaults, isSearching), { initialProps: { isSearching: searching } });

  it("starts with what it was told to open, and nothing else", () => {
    const { result } = expansion([ "records" ]);

    expect(result.current.isDatabaseOpen("records")).toBe(true);
    expect(result.current.isDatabaseOpen("registry")).toBe(false);
  });

  it("opens and shuts a node", () => {
    const { result } = expansion();

    act(() => { result.current.setOpen("records", true); });
    expect(result.current.isDatabaseOpen("records")).toBe(true);

    act(() => { result.current.setOpen("records", false); });
    expect(result.current.isDatabaseOpen("records")).toBe(false);
  });

  it("opens everything, and shuts everything", () => {
    const { result } = expansion();

    act(() => { result.current.expandAll([ "records", "registry" ]); });
    expect(result.current.isDatabaseOpen("records")).toBe(true);
    expect(result.current.isDatabaseOpen("registry")).toBe(true);

    act(() => { result.current.collapseAll([ "records", "registry" ]); });
    expect(result.current.isDatabaseOpen("records")).toBe(false);
  });

  it("opens every database while a search is running", () => {
    expect(expansion([], true).result.current.isDatabaseOpen("records")).toBe(true);
  });

  it("opens a collection during a search only up to forty matches", () => {
    const { result } = expansion([], true);

    expect(result.current.isCollectionOpen("records/Patient", 40)).toBe(true);
    expect(result.current.isCollectionOpen("records/Patient", 41)).toBe(false);
  });

  it("keeps a collection the reader opened open however many matched", () => {
    expect(expansion([ "records/Patient" ], true).result.current.isCollectionOpen("records/Patient", 400)).toBe(true);
  });

  it("shuts a node the reader shuts during a search, though the search opened it", () => {
    const { result } = expansion([], true);

    act(() => { result.current.setOpen("records", false); });
    act(() => { result.current.collapseAll([ "records/Patient" ]); });

    expect(result.current.isDatabaseOpen("records")).toBe(false);
    expect(result.current.isCollectionOpen("records/Patient", 1)).toBe(false);
  });

  it("goes back to how the reader left the tree once the search ends", () => {
    const { result, rerender } = expansion([ "records" ], false);
    rerender({ isSearching: true });

    act(() => { result.current.setOpen("records", false); });
    act(() => { result.current.setOpen("registry", false); });
    rerender({ isSearching: false });

    expect(result.current.isDatabaseOpen("records")).toBe(true);
    expect(result.current.isDatabaseOpen("registry")).toBe(false);
  });

  it("forgets one search's choices when the next begins", () => {
    const { result, rerender } = expansion([], true);
    act(() => { result.current.setOpen("records", false); });
    rerender({ isSearching: false });

    rerender({ isSearching: true });

    expect(result.current.isDatabaseOpen("records")).toBe(true);
  });
});

describe("a group's own view of the selection", () => {
  const group = (keys: string[], chosen: string[]) => {
    const onChange = vi.fn();
    const { result } = renderHook(() => useGroupSelection(keys), {
      wrapper: ({ children }: { children: ReactNode }) => (
        <SelectionProvider value={chosen} onChange={onChange}>{children}</SelectionProvider>),
    });
    return { result, onChange };
  };

  it("counts how many of its own fields are chosen", () => {
    expect(group([ "a", "b", "c" ], [ "a", "b" ]).result.current.selectedCount).toBe(2);
  });

  it("shows nothing, some, or all of itself chosen", () => {
    expect(group([ "a", "b" ], []).result.current.state).toBe("none");
    expect(group([ "a", "b" ], [ "a" ]).result.current.state).toBe("some");
    expect(group([ "a", "b" ], [ "a", "b" ]).result.current.state).toBe("all");
  });

  it("counts only its own keys", () => {
    expect(group([ "a" ], [ "a", "z" ]).result.current.selectedCount).toBe(1);
    expect(group([ "a" ], [ "a", "z" ]).result.current.state).toBe("all");
  });

  it("takes the rest of the group in one change when some of it is chosen, losing no choice", () => {
    const { result, onChange } = group([ "a", "b" ], [ "a" ]);

    act(() => { result.current.toggleAll(); });

    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith([ "a", "b" ]);
  });

  it("gives the whole group up in one change when all of it is chosen", () => {
    const { result, onChange } = group([ "a", "b" ], [ "a", "b", "z" ]);

    act(() => { result.current.toggleAll(); });

    expect(onChange).toHaveBeenCalledWith([ "z" ]);
  });
});

describe("reaching a catalogue that is not there", () => {
  it("says so rather than drawing a tree with nothing in it", () => {
    // React logs the throw as well
    const quiet = vi.spyOn(console, "error").mockImplementation(() => undefined);

    expect(() => renderHook(() => useCatalogue())).toThrow(/CatalogueProvider/);

    quiet.mockRestore();
  });

  it("says so when a selection is asked for outside a provider", () => {
    const quiet = vi.spyOn(console, "error").mockImplementation(() => undefined);

    expect(() => renderHook(() => useSelection())).toThrow(/SelectionProvider/);

    quiet.mockRestore();
  });
});

describe("what a catalogue provider hands down", () => {
  const provided = (display?: Parameters<typeof CatalogueProvider>[0]["display"]) =>
    renderHook(() => useCatalogue(), {
      wrapper: ({ children }: { children: ReactNode }) => (
        <CatalogueProvider catalogue={sampleCatalogue()} display={display}>{children}</CatalogueProvider>),
    }).result.current;

  it("hands down the catalogue it was given, and the defaults it resolves", () => {
    const context = provided();

    expect(context.catalogue).toEqual(sampleCatalogue());
    expect(context.display.tree.field.showPhi).toBe(true);
  });

  it("lays a deployment's overrides over the defaults", () => {
    const context = provided({ tree: { field: { showIdentifier: true } } });

    expect(context.display.tree.field.showIdentifier).toBe(true);
    expect(context.display.tree.field.showPhi).toBe(true);
  });
});
