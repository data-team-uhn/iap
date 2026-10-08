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

import { categoryChoices, schemaChoices, submissionChoices } from "@iap/submissions/schemaModel";

import { CATEGORIES, SCHEMAS } from "./schemas.fixture";

describe("schemaChoices", () => {
  it("offers the active version of each active schema", () => {
    expect(schemaChoices(SCHEMAS)).toEqual([ {
      path: "/Schemas/timeOffRequest/v1",
      title: "Time off request",
      version: "1.0",
      description: "Asking for a day off",
    } ]);
  });

  it("offers nothing for a retired schema, whatever its versions say", () => {
    const retired = { s: { ...SCHEMAS.timeOffRequest, tags: [ "retired" ] } };

    expect(schemaChoices(retired)).toEqual([]);
  });

  it("offers nothing for a live schema whose versions are all retired", () => {
    const noVersion = { s: { ...SCHEMAS.timeOffRequest, v1: { ...SCHEMAS.timeOffRequest.v1, tags: [ "retired" ] } } };

    expect(schemaChoices(noVersion)).toEqual([]);
  });

  it("offers nothing for a version that is still a draft, or carries no lifecycle tag", () => {
    const draft = { s: { ...SCHEMAS.timeOffRequest, v1: { ...SCHEMAS.timeOffRequest.v1, tags: [ "draft" ] } } };
    const untagged = { s: { ...SCHEMAS.timeOffRequest, v1: { ...SCHEMAS.timeOffRequest.v1, tags: undefined } } };

    expect(schemaChoices(draft)).toEqual([]);
    expect(schemaChoices(untagged)).toEqual([]);
  });

  it("falls back to the node name for a schema with no title", () => {
    const untitled = { s: { ...SCHEMAS.timeOffRequest, title: undefined } };

    expect(schemaChoices(untitled)[0].title).toBe("timeOffRequest");
  });

  it("ignores the homepage's own properties, which are not schemas", () => {
    expect(schemaChoices({ "jcr:primaryType": "sch:SchemasHomepage", "count": 3 })).toEqual([]);
  });

  // Both node types allow any other child, so a listing carries more than schemas and versions
  it("passes over a child of the homepage that is not a schema", () => {
    const alien = {
      s: SCHEMAS.timeOffRequest,
      "rep:policy": { "jcr:primaryType": "rep:ACL", "@path": "/Schemas/rep:policy", "tags": [ "active" ] },
    };

    expect(schemaChoices(alien).map(choice => choice.path)).toEqual([ "/Schemas/timeOffRequest/v1" ]);
  });

  it("does not take a schema's other children for one of its versions", () => {
    const withNotes = {
      s: {
        ...SCHEMAS.timeOffRequest,
        v1: { ...SCHEMAS.timeOffRequest.v1, tags: [ "draft" ] },
        notes: {
          "jcr:primaryType": "nt:unstructured",
          "@path": "/Schemas/timeOffRequest/notes",
          "tags": [ "active" ],
        },
      },
    };

    expect(schemaChoices(withNotes)).toEqual([]);
  });

  // Oak permits an empty value for a mandatory property, and "" is not nullish, so the fallback has
  // to fire on it rather than only on a missing title
  it("falls back to the node name for a schema whose title is empty", () => {
    const blank = { s: { ...SCHEMAS.timeOffRequest, title: "" } };

    expect(schemaChoices(blank)[0].title).toBe("timeOffRequest");
  });

  it("offers a nameless schema rather than dropping it", () => {
    // Title and node name are both mandatory in practice. Content that somehow lacks them is still
    // offered: a choice missing its label beats a missing choice
    const nameless = { s: { ...SCHEMAS.timeOffRequest, title: undefined, "@name": undefined } };

    expect(schemaChoices(nameless)[0]).toMatchObject({ title: "", path: "/Schemas/timeOffRequest/v1" });
  });

  it("offers a version with no label", () => {
    const unlabelled = { s: { ...SCHEMAS.timeOffRequest, v1: { ...SCHEMAS.timeOffRequest.v1, version: undefined } } };

    expect(schemaChoices(unlabelled)[0]).toMatchObject({ version: "" });
  });
});

describe("categoryChoices", () => {
  it("offers each open top category under its own name, raising against its schema's active version", () => {
    expect(categoryChoices(CATEGORIES, schemaChoices(SCHEMAS))).toEqual([ {
      path: "/Schemas/timeOffRequest/v1",
      version: "",
      title: "Time away",
      description: "Any request to be away from work",
    } ]);
  });

  it("falls back to the schema's name and description when the category has none", () => {
    const bare = { away: { "jcr:primaryType": "cat:Category", "schemaVersion": CATEGORIES.away.schemaVersion } };
    expect(categoryChoices(bare, schemaChoices(SCHEMAS))).toEqual([ {
      path: "/Schemas/timeOffRequest/v1",
      version: "",
      title: "Time off request",
      description: "Asking for a day off",
    } ]);
  });

  it("offers nothing when no top category names a schema", () => {
    const unbound = { away: { "jcr:primaryType": "cat:Category", "label": "Time away", "schemaVersion": "a-uuid" } };
    expect(categoryChoices(unbound, schemaChoices(SCHEMAS))).toEqual([]);
    expect(categoryChoices({ away: { "jcr:primaryType": "cat:Category" } }, schemaChoices(SCHEMAS))).toEqual([]);
  });
});

describe("submissionChoices", () => {
  const unbound = {
    "jcr:primaryType": "sch:Schema", "@path": "/Schemas/leave", "@name": "leave", "title": "Leave",
    "v2": { "jcr:primaryType": "sch:SchemaVersion", "@path": "/Schemas/leave/v2", "version": "2.0", "tags": [ "active" ] },
  };

  it("offers the top categories first, then the open schemas no category offers", () => {
    expect(submissionChoices(CATEGORIES, { ...SCHEMAS, leave: unbound }).map(choice => choice.title))
      .toEqual([ "Time away", "Leave" ]);
  });

  it("offers the open schemas as they are when there are no categories", () => {
    expect(submissionChoices({}, SCHEMAS)).toEqual(schemaChoices(SCHEMAS));
  });
});
