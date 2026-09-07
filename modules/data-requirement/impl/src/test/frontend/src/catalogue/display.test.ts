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

import { describe, expect, it } from "vitest";

import { defaultDisplayConfig, resolveDisplayConfig } from "@iap/data-requirement/catalogue/display";

describe("what a catalogue shows", () => {
  it("shows what a submitter needs and leaves the rest off", () => {
    const { field, collection } = defaultDisplayConfig.tree;

    // Identifiers and cardinality notation are for whoever engineers the data, not whoever asks for it
    expect(field.showIdentifier).toBe(false);
    expect(field.showCardinality).toBe(false);
    expect(field.showType).toBe(false);
    expect(collection.showIdentifier).toBe(false);
    // Whether a field identifies a person is governance, and it stays visible where fields are picked
    expect(field.showPhi).toBe(true);
    expect(field.showDescription).toBe(true);
  });

  // The platform ships no vocabulary of its own: the words are a deployment's
  it("preserves no words until a deployment names some", () => {
    expect(defaultDisplayConfig.preserveWords).toEqual([]);
  });

  it("hands back the defaults when nothing is overridden", () => {
    expect(resolveDisplayConfig()).toEqual(defaultDisplayConfig);
  });

  // The failure a shallow merge produces, and which nothing would report
  it("keeps the flags beside the one that was overridden", () => {
    const resolved = resolveDisplayConfig({ tree: { field: { showIdentifier: true } } });

    expect(resolved.tree.field.showIdentifier).toBe(true);
    expect(resolved.tree.field.showPhi).toBe(true);
    expect(resolved.tree.field.showDescription).toBe(true);
    expect(resolved.tree.collection.showIdentifier).toBe(false);
  });

  it("takes a deployment's own vocabulary", () => {
    expect(resolveDisplayConfig({ preserveWords: [ "Medly" ] }).preserveWords).toEqual([ "Medly" ]);
  });

  it("adds to the label overrides rather than replacing them", () => {
    const resolved = resolveDisplayConfig({ labelOverrides: { mrn: "MRN" } });

    expect(resolved.labelOverrides.mrn).toBe("MRN");
    expect(resolved.labelOverrides.id).toBe("ID");
  });

  it("overrides only the collection flags when only those are given", () => {
    const resolved = resolveDisplayConfig({ tree: { collection: { showIdentifier: true } } });

    expect(resolved.tree.collection.showIdentifier).toBe(true);
    expect(resolved.tree.field.showPhi).toBe(true);
  });
});
