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

import { describe, expect, it, vi } from "vitest";

import { readCatalogue, saveDataSelection } from "@iap/data-requirement/catalogueApi";
import type { SerializedNode } from "@iap/frontend-commons/serializedNode";

// Posting an event has its own suite, and is mocked here
const sendEvent = vi.hoisted(() => vi.fn());

vi.mock("@iap/frontend-commons/workflowEvents", () => ({ sendEvent }));

// A serialized catalogue version, as the repository renders one: properties and children are both
// plain keys, and only `sling:resourceType` says which level a child belongs to.
function serializedVersion(): SerializedNode {
  return {
    "jcr:primaryType": "datareq:CatalogueVersion",
    "sling:resourceType": "datareq/CatalogueVersion",
    version: "2026-02",
    visits: {
      "sling:resourceType": "datareq/Database",
      identifier: "visits",
      label: "Clinic visits",
      description: "One record per attendance",
      Visit: {
        "sling:resourceType": "datareq/Collection",
        identifier: "Visit",
        label: "Visit",
        visitDate: {
          "sling:resourceType": "datareq/Field",
          identifier: "visitDate",
          key: "visits/Visit/visitDate",
          label: "Date of visit",
          description: "The day the person attended",
          cardinality: "1..1",
          dataType: "date",
          phi: true,
        },
        department: {
          "sling:resourceType": "datareq/Field",
          identifier: "department",
          key: "visits/Visit/department",
          cardinality: "1..1",
          dataType: "code",
          phi: false,
        },
        unassessed: {
          "sling:resourceType": "datareq/Field",
          identifier: "unassessed",
          key: "visits/Visit/unassessed",
        },
      },
    },
  };
}

function visitsOf(node: SerializedNode) {
  return readCatalogue(node).databases[0];
}

function fieldsOf(node: SerializedNode) {
  return visitsOf(node).collections[0].fields;
}

function visitCollection(node: SerializedNode): SerializedNode {
  return (node.visits as SerializedNode).Visit as SerializedNode;
}

describe("reading a catalogue version out of what the repository serves", () => {
  it("builds the three levels, and counts each database's fields", () => {
    const built = readCatalogue(serializedVersion());

    expect(built.databases).toHaveLength(1);
    expect(built.databases[0].label).toBe("Clinic visits");
    expect(built.databases[0].collections).toHaveLength(1);
    expect(built.databases[0].fieldCount).toBe(3);
  });

  it("takes each field's key from the server, which refuses to save any other", () => {
    const node = serializedVersion();
    (visitCollection(node).department as SerializedNode).key = "as/the/server/says";

    expect(fieldsOf(node).map(field => field.key))
      .toEqual([ "visits/Visit/visitDate", "as/the/server/says", "visits/Visit/unassessed" ]);
  });

  it("shows the curated label, and says when it fell back to the identifier", () => {
    const [ curated, uncurated ] = fieldsOf(serializedVersion());

    expect(curated.label).toBe("Date of visit");
    expect(curated.labelIsFallback).toBe(false);
    expect(uncurated.labelIsFallback).toBe(true);
  });

  it("keeps unassessed apart from assessed-and-clear", () => {
    const [ flagged, clear, unassessed ] = fieldsOf(serializedVersion());

    expect(flagged.phi).toBe(true);
    expect(clear.phi).toBe(false);
    expect(unassessed.phi).toBeUndefined();
  });

  it("drops a field with no identifier", () => {
    const node = serializedVersion();
    visitCollection(node).nameless = { "sling:resourceType": "datareq/Field", key: "visits/Visit/", label: "No name" };

    expect(visitsOf(node).fieldCount).toBe(3);
  });

  it("drops a field the server gave no key, since it could never be chosen", () => {
    const node = serializedVersion();
    visitCollection(node).keyless = { "sling:resourceType": "datareq/Field", identifier: "keyless" };

    expect(visitsOf(node).fieldCount).toBe(3);
  });

  it("drops a collection with no identifier", () => {
    const node = serializedVersion();
    (node.visits as SerializedNode).nameless = { "sling:resourceType": "datareq/Collection" };

    expect(visitsOf(node).collections).toHaveLength(1);
  });

  it("drops a database with no identifier", () => {
    const node = serializedVersion();
    node.nameless = { "sling:resourceType": "datareq/Database" };

    expect(readCatalogue(node).databases).toHaveLength(1);
  });

  it("names a database without a label of its own by its identifier, unchanged", () => {
    const node = serializedVersion();
    (node.visits as SerializedNode).label = "  ";

    expect(visitsOf(node).label).toBe("visits");
  });

  it("ignores a child of some other type", () => {
    const node = serializedVersion();
    node.somethingElse = { "sling:resourceType": "datareq/Selection", identifier: "nope" };

    expect(readCatalogue(node).databases).toHaveLength(1);
  });
});

describe("recording what was chosen", () => {
  function sent(): URLSearchParams {
    return sendEvent.mock.lastCall?.[3] as URLSearchParams;
  }

  it("sends a saveDataSelection event to the submission", async () => {
    const doFetch = vi.fn();

    await saveDataSelection(doFetch, "/Submissions/one", "dataNeeded", [ "a/B/c" ]);

    expect(sendEvent).toHaveBeenCalledWith(doFetch, "/Submissions/one", "saveDataSelection", expect.anything());
  });

  it("names the requirement and repeats each chosen key", async () => {
    await saveDataSelection(vi.fn(), "/Submissions/one", "dataNeeded", [ "a/B/c", "a/B/d" ]);

    expect(sent().get("requirement")).toBe("dataNeeded");
    expect(sent().getAll("fields")).toEqual([ "a/B/c", "a/B/d" ]);
  });

  it("sends no keys at all when the selection is cleared", async () => {
    await saveDataSelection(vi.fn(), "/Submissions/one", "dataNeeded", []);

    expect(sent().getAll("fields")).toEqual([]);
  });
});
