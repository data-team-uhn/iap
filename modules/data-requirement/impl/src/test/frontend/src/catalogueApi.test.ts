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

import { afterEach, describe, expect, it, vi } from "vitest";

import {
  fetchCatalogue,
  readCatalogue,
  saveDataSelection,
} from "@iap/data-requirement/catalogueApi";
import type { JsonNode } from "@iap/submissions/jsonNode";

// A serialized catalogue version, as the repository renders one: properties and children are both
// plain keys, and only `sling:resourceType` says which level a child belongs to.
function serializedVersion(): JsonNode {
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
        // Nobody has assessed this one, which is not the same as having found it clear
        unassessed: {
          "sling:resourceType": "datareq/Field",
          identifier: "unassessed",
        },
      },
    },
  };
}

function fieldsOf(node: JsonNode) {
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

  // The compatibility contract with every selection ever made: the server builds the same string in
  // `Field.getKey()`, and the save refuses a key the catalogue does not offer. If these two ever
  // disagree, every save fails and nothing else says why.
  it("builds a field key the way the server does", () => {
    expect(fieldsOf(serializedVersion())[0].key).toBe("visits/Visit/visitDate");
  });

  it("shows the curated label, and says when it fell back to the identifier", () => {
    const [ curated, uncurated ] = fieldsOf(serializedVersion());

    expect(curated.label).toBe("Date of visit");
    expect(curated.labelIsFallback).toBe(false);
    expect(uncurated.labelIsFallback).toBe(true);
  });

  // Three answers, not two. A field nobody has assessed must not read as one assessed and found
  // clear, so an absent property stays absent rather than becoming false.
  it("keeps unassessed apart from assessed-and-clear", () => {
    const [ flagged, clear, unassessed ] = fieldsOf(serializedVersion());

    expect(flagged.phi).toBe(true);
    expect(clear.phi).toBe(false);
    expect(unassessed.phi).toBeUndefined();
  });

  it("drops a field with no identifier, because nothing could ever be chosen of it", () => {
    const node = serializedVersion();
    const collection = (node.visits as JsonNode).Visit as JsonNode;
    collection.nameless = { "sling:resourceType": "datareq/Field", label: "No identifier" };

    expect(readCatalogue(node).totalFields).toBe(3);
  });

  it("drops a collection with no identifier", () => {
    const node = serializedVersion();
    (node.visits as JsonNode).nameless = { "sling:resourceType": "datareq/Collection" };

    expect(readCatalogue(node).totalCollections).toBe(1);
  });

  it("drops a database with no identifier", () => {
    const node = serializedVersion();
    node.nameless = { "sling:resourceType": "datareq/Database" };

    expect(readCatalogue(node).databases).toHaveLength(1);
  });

  // Unlike a collection, a database is not humanised from its identifier when it carries no label:
  // whoever published the catalogue named it, and turning `visits` into `Visits` would be inventing
  // a name rather than reading one. Falling back to the identifier verbatim is what that means.
  it("names an unlabelled database by its identifier, unchanged", () => {
    const node = serializedVersion();
    delete (node.visits as JsonNode).label;

    expect(readCatalogue(node).databases[0].label).toBe("visits");
  });

  it("ignores a child of some other type", () => {
    const node = serializedVersion();
    node.somethingElse = { "sling:resourceType": "datareq/Selection", identifier: "nope" };

    expect(readCatalogue(node).databases).toHaveLength(1);
  });
});

describe("fetching a catalogue version", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  // The depth is what keeps the request to the tree and nothing under it. Asked for explicitly so
  // that a field growing children later does not silently start pulling them over the wire.
  it("asks for three levels below the version", async () => {
    const fetchMock = vi.fn((_url: string) => Promise.resolve(response(serializedVersion())));
    vi.stubGlobal("fetch", fetchMock);

    await fetchCatalogue("/Catalogues/demoRegistry/v1");

    expect(fetchMock).toHaveBeenCalledWith("/Catalogues/demoRegistry/v1.3.json");
  });

  it("reports a version it could not read", async () => {
    vi.stubGlobal("fetch", () => Promise.resolve(response({}, false, 404)));

    await expect(fetchCatalogue("/Catalogues/gone/v1")).rejects.toThrow("(404)");
  });

  it("reports a body that is not a node", async () => {
    vi.stubGlobal("fetch", () => Promise.resolve(response([ "not", "a", "node" ])));

    await expect(fetchCatalogue("/Catalogues/odd/v1")).rejects.toThrow("could not be read");
  });
});

describe("recording what was chosen", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function sent(fetchMock: ReturnType<typeof vi.fn>): URLSearchParams {
    const [ , init ] = fetchMock.mock.calls[0] as [string, RequestInit];
    return init.body as URLSearchParams;
  }

  // The event is named by a selector, so `.json` has to follow it: without the extension Sling reads
  // the event name as the format, and the POST would mean `save` instead.
  it("posts the event as a selector, with the extension after it", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) =>
      Promise.resolve(response({})));
    vi.stubGlobal("fetch", fetchMock);

    await saveDataSelection("/Submissions/one", "dataNeeded", [ "a/B/c" ]);

    expect(fetchMock.mock.calls[0][0]).toBe("/Submissions/one.saveDataSelection.json");
  });

  it("names the requirement and repeats each chosen key", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) =>
      Promise.resolve(response({})));
    vi.stubGlobal("fetch", fetchMock);

    await saveDataSelection("/Submissions/one", "dataNeeded", [ "a/B/c", "a/B/d" ]);

    const body = sent(fetchMock);
    expect(body.get("requirement")).toBe("dataNeeded");
    expect(body.getAll("fields")).toEqual([ "a/B/c", "a/B/d" ]);
  });

  // Choosing nothing is a legitimate save rather than a no-op: it is how a selection is cleared, and
  // the handler reads an absent `fields` that way.
  it("sends no keys at all when the selection is cleared", async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) =>
      Promise.resolve(response({})));
    vi.stubGlobal("fetch", fetchMock);

    await saveDataSelection("/Submissions/one", "dataNeeded", []);

    expect(sent(fetchMock).getAll("fields")).toEqual([]);
  });

  it("surfaces the engine's own reason for a refusal", async () => {
    vi.stubGlobal("fetch", () => Promise.resolve(
      response({ error: "This request has been submitted and can no longer be changed" },
        false, 403)));

    await expect(saveDataSelection("/Submissions/one", "dataNeeded", []))
      .rejects.toThrow("can no longer be changed");
  });

  it("falls back to the status when a refusal carries no reason", async () => {
    vi.stubGlobal("fetch", () => Promise.resolve({
      ok: false,
      status: 500,
      json: () => Promise.reject(new Error("not json")),
    } as unknown as Response));

    await expect(saveDataSelection("/Submissions/one", "dataNeeded", []))
      .rejects.toThrow("(500)");
  });
});
