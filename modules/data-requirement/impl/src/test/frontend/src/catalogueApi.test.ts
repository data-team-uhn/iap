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

import {
  fetchCatalogue,
  readCatalogue,
  saveDataSelection,
} from "@iap/data-requirement/catalogueApi";
import type { SerializedNode } from "@iap/frontend-commons/serializedNode";

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
          label: "Date of visit",
          description: "The day the person attended",
          cardinality: "1..1",
          dataType: "date",
          phi: true,
        },
        department: {
          "sling:resourceType": "datareq/Field",
          identifier: "department",
          cardinality: "1..1",
          dataType: "code",
          phi: false,
        },
        // Not assessed
        unassessed: {
          "sling:resourceType": "datareq/Field",
          identifier: "unassessed",
        },
      },
    },
  };
}

function fieldsOf(node: SerializedNode) {
  return readCatalogue(node).databases[0].collections[0].fields;
}

function response(body: unknown, ok = true, status = 200): Response {
  return {
    ok,
    status,
    url: "",
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

describe("reading a catalogue version out of what the repository serves", () => {
  it("builds the three levels", () => {
    const built = readCatalogue(serializedVersion());

    expect(built.databases).toHaveLength(1);
    expect(built.databases[0].label).toBe("Clinic visits");
    expect(built.databases[0].collections).toHaveLength(1);
    expect(built.totalCollections).toBe(1);
    expect(built.totalFields).toBe(3);
    expect(built.databases[0].fieldCount).toBe(3);
  });

  // The server builds the same key in `Field.getKey()`, and refuses to save any other
  it("builds a field key the way the server does", () => {
    expect(fieldsOf(serializedVersion())[0].key).toBe("visits/Visit/visitDate");
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

  it("drops a field with no identifier, because nothing could ever be chosen of it", () => {
    const node = serializedVersion();
    const collection = (node.visits as SerializedNode).Visit as SerializedNode;
    collection.nameless = { "sling:resourceType": "datareq/Field", label: "No identifier" };

    expect(readCatalogue(node).totalFields).toBe(3);
  });

  it("drops a collection with no identifier", () => {
    const node = serializedVersion();
    (node.visits as SerializedNode).nameless = { "sling:resourceType": "datareq/Collection" };

    expect(readCatalogue(node).totalCollections).toBe(1);
  });

  it("drops a database with no identifier", () => {
    const node = serializedVersion();
    node.nameless = { "sling:resourceType": "datareq/Database" };

    expect(readCatalogue(node).databases).toHaveLength(1);
  });

  it("names an unlabelled database by its identifier, unchanged", () => {
    const node = serializedVersion();
    delete (node.visits as SerializedNode).label;

    expect(readCatalogue(node).databases[0].label).toBe("visits");
  });

  it("ignores a child of some other type", () => {
    const node = serializedVersion();
    node.somethingElse = { "sling:resourceType": "datareq/Selection", identifier: "nope" };

    expect(readCatalogue(node).databases).toHaveLength(1);
  });
});

describe("fetching a catalogue version", () => {
  it("asks for three levels below the version", async () => {
    const fetchMock = vi.fn((_url: string) => Promise.resolve(response(serializedVersion())));

    await fetchCatalogue(fetchMock, "/Catalogues/demoRegistry/v1");

    expect(fetchMock).toHaveBeenCalledWith("/Catalogues/demoRegistry/v1.3.json");
  });

  it("reports a version it could not read", async () => {
    const doFetch = () => Promise.resolve(response({}, false, 404));

    await expect(fetchCatalogue(doFetch, "/Catalogues/gone/v1")).rejects.toThrow("(404)");
  });

  it("reports a body that is not a node", async () => {
    const doFetch = () => Promise.resolve(response([ "not", "a", "node" ]));

    await expect(fetchCatalogue(doFetch, "/Catalogues/odd/v1")).rejects.toThrow("could not be read");
  });
});

describe("recording what was chosen", () => {
  function sent(fetchMock: ReturnType<typeof vi.fn>): URLSearchParams {
    const [ , init ] = fetchMock.mock.calls[0] as [string, RequestInit];
    return init.body as URLSearchParams;
  }

  it("posts the event as a selector, with the extension after it", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) =>
      Promise.resolve(response({})));

    await saveDataSelection(fetchMock, "/Submissions/one", "dataNeeded", [ "a/B/c" ]);

    expect(fetchMock.mock.calls[0][0]).toBe("/Submissions/one.saveDataSelection.json");
  });

  it("names the requirement and repeats each chosen key", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) =>
      Promise.resolve(response({})));

    await saveDataSelection(fetchMock, "/Submissions/one", "dataNeeded", [ "a/B/c", "a/B/d" ]);

    const body = sent(fetchMock);
    expect(body.get("requirement")).toBe("dataNeeded");
    expect(body.getAll("fields")).toEqual([ "a/B/c", "a/B/d" ]);
  });

  it("sends no keys at all when the selection is cleared", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) =>
      Promise.resolve(response({})));

    await saveDataSelection(fetchMock, "/Submissions/one", "dataNeeded", []);

    expect(sent(fetchMock).getAll("fields")).toEqual([]);
  });

  it("surfaces the engine's own reason for a refusal", async () => {
    const doFetch = () => Promise.resolve(
      response({ error: "This request has been submitted and can no longer be changed" },
        false, 403));

    await expect(saveDataSelection(doFetch, "/Submissions/one", "dataNeeded", []))
      .rejects.toThrow("can no longer be changed");
  });

  it("falls back to the status when a refusal carries no reason", async () => {
    const doFetch = () => Promise.resolve({
      ok: false,
      status: 500,
      json: () => Promise.reject(new Error("not json")),
    } as unknown as Response);

    await expect(saveDataSelection(doFetch, "/Submissions/one", "dataNeeded", []))
      .rejects.toThrow("(500)");
  });
});
