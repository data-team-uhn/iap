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
  countSchemas, descriptionOf, fieldsOf, labelOf, offers, pathOf, schemaNameFromRoute, schemasOf, tagsOf, titleOf,
  versionsOf,
} from "@iap/schemas/schemaModel";

import { HOMEPAGE, withPaths } from "./schemaServer.fixture";

describe("schemaModel", () => {
  it("reads schemas and their versions, leaving other children out", () => {
    const schemas = schemasOf(withPaths("/Schemas", HOMEPAGE));

    expect(schemas.map(titleOf)).toEqual([ "Clinical study", "Idea", "Legacy" ]);
    expect(pathOf(schemas[0])).toBe("/Schemas/study");
    expect(versionsOf(schemas[0]).map(version => [ labelOf(version), tagsOf(version) ])).toEqual([
      [ "1.0", [ "retired" ] ],
      [ "2.0", [ "active" ] ],
      [ "3.0", [ "draft" ] ],
    ]);
    expect(descriptionOf(versionsOf(schemas[0])[1])).toBe("Current");
    expect(tagsOf(schemas[2])).toEqual([ "retired" ]);
  });

  it("falls back to node names, and ignores what it cannot read", () => {
    const schema = {
      "@name": "bare",
      "title": " ",
      "@events": "update",
      "@fields": [ { name: "title", label: "Title" }, { label: "No name" }, "title" ],
      "v9": {
        "jcr:primaryType": "sch:SchemaVersion", "@name": "v9", "tags": [ "draft", 3 ], "@events": [ "discard", null ],
      },
    };

    expect(titleOf(schema)).toBe("bare");
    expect(offers(schema, "update")).toBe(false);
    expect(fieldsOf(schema).map(field => field.name)).toEqual([ "title" ]);
    expect(fieldsOf(versionsOf(schema)[0])).toEqual([]);
    const [ version ] = versionsOf(schema);
    expect(labelOf(version)).toBe("v9");
    expect(tagsOf(version)).toEqual([ "draft" ]);
    expect(offers(version, "discard")).toBe(true);
  });

  it("lists versions by label, comparing numbers as numbers", () => {
    const schema = {
      "@name": "many",
      "b": { "jcr:primaryType": "sch:SchemaVersion", "version": "10.0" },
      "a": { "jcr:primaryType": "sch:SchemaVersion", "version": "2.0" },
      "c": { "jcr:primaryType": "sch:SchemaVersion", "version": "1.1" },
    };

    expect(versionsOf(schema).map(labelOf)).toEqual([ "1.1", "2.0", "10.0" ]);
  });

  it("counts the lifecycle tags", () => {
    expect(countSchemas(schemasOf(HOMEPAGE))).toEqual({ active: 2, drafts: 2, retired: 1 });
  });

  it("reads the schema name out of its page's route", () => {
    expect(schemaNameFromRoute("/admin/schemas/study")).toBe("study");
    expect(schemaNameFromRoute("/admin/schemas/my%20study/")).toBe("my study");
    expect(schemaNameFromRoute("")).toBe("");
  });
});
