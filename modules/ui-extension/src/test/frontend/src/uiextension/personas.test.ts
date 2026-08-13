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

import {
  PERSONAS,
  STORE_KEY,
  availablePersonas,
  getActivePersona,
  personaLabel,
  setActivePersona,
  subscribeToPersona,
} from "@iap/ui-extension/personas";

// The store lives on `window` (deliberately - see personas.ts), so it survives between tests;
// return it to the default so each test starts from the same place. Through the exported key, so
// that renaming it cannot leave these tests quietly deleting a property nobody reads.
afterEach(() => {
  Reflect.deleteProperty(window, STORE_KEY);
});

describe("the persona catalogue", () => {
  it("starts with the least permissive persona, which is what the default depends on", () => {
    expect(PERSONAS[0]).toBe("submitter");
    expect(PERSONAS).toContain("reviewer");
    expect(PERSONAS).toContain("administrator");
  });

  it("offers every persona for now, since roles do not exist yet", () => {
    expect(availablePersonas()).toEqual([ ...PERSONAS ]);
  });

  it("hands out a copy, so a caller cannot corrupt the catalogue", () => {
    const personas = availablePersonas();
    personas.push("intruder");

    expect(availablePersonas()).not.toContain("intruder");
  });

  it("labels the personas for display, falling back to the raw value", () => {
    expect(personaLabel("submitter")).toBe("Submitter");
    expect(personaLabel("reviewer")).toBe("Reviewer");
    expect(personaLabel("administrator")).toBe("Administrator");
    expect(personaLabel("something-else")).toBe("something-else");
  });
});

describe("the active persona", () => {
  it("defaults to the least permissive persona available", () => {
    expect(getActivePersona()).toBe(availablePersonas()[0]);
  });

  it("changes when a persona is chosen", () => {
    setActivePersona("reviewer");

    expect(getActivePersona()).toBe("reviewer");
  });

  it("refuses a persona that is not on offer, so the constraint holds even from stale callers", () => {
    setActivePersona("reviewer");

    setActivePersona("intruder");

    expect(getActivePersona()).toBe("reviewer");
  });

  it("falls back to the default if the stored persona is no longer available", () => {
    (window as unknown as Record<string, { active: string }>)[STORE_KEY] = { active: "retired-role" };

    expect(getActivePersona()).toBe(availablePersonas()[0]);
  });

  it("notifies subscribers of a change, and stops once unsubscribed", () => {
    let changes = 0;
    const unsubscribe = subscribeToPersona(() => changes++);

    setActivePersona("reviewer");
    expect(changes).toBe(1);

    unsubscribe();
    setActivePersona("administrator");
    expect(changes).toBe(1);
  });

  it("does not notify when the chosen persona is already active", () => {
    setActivePersona("reviewer");
    let changes = 0;
    const unsubscribe = subscribeToPersona(() => changes++);

    setActivePersona("reviewer");

    expect(changes).toBe(0);
    unsubscribe();
  });

  // The reason the store hangs off `window` at all: the switcher and the components reading the
  // persona are separate webpack bundles, and this module may legitimately be duplicated into both.
  // Duplicate copies must still agree, which they do only because the state is not in a closure.
  it("is shared with a separately loaded copy of this module", async () => {
    setActivePersona("administrator");

    // A genuinely fresh instance of the module, standing in for the second bundled copy.
    vi.resetModules();
    const reloaded = await import("@iap/ui-extension/personas");

    expect(reloaded.getActivePersona()).toBe("administrator");
  });
});
