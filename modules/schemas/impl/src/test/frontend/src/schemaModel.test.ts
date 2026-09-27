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
  countSchemas, descriptionOf, fieldsOf, labelOf, latestVersion, nextVersionLabel, offers, pathOf, schemaNameFromRoute,
  schemasOf, tagsOf, titleOf, versionNameFromRoute, versionsOf,
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

  it("proposes the next whole number as a new version's label", () => {
    const [ study, idea ] = schemasOf(HOMEPAGE);
    expect(nextVersionLabel(study)).toBe("4.0");
    expect(nextVersionLabel(idea)).toBe("1.0");
    expect(nextVersionLabel({ "a": { "jcr:primaryType": "sch:SchemaVersion", "version": "draft" } })).toBe("2.0");
    expect(nextVersionLabel({})).toBe("1.0");
  });

  it("finds the version made last, or the last by label when it cannot tell", () => {
    const version = (label: string, created?: string) =>
      ({ "jcr:primaryType": "sch:SchemaVersion", "version": label, "jcr:created": created });
    expect(labelOf(latestVersion({
      a: version("1.0", "2026-01-01T00:00:00.000-05:00"),
      b: version("2.0", "2025-01-01T00:00:00.000-05:00"),
    })!)).toBe("1.0");
    expect(labelOf(latestVersion({ a: version("1.0"), b: version("2.0") })!)).toBe("2.0");
    expect(latestVersion({})).toBeUndefined();
  });

  it("counts the lifecycle tags", () => {
    expect(countSchemas(schemasOf(HOMEPAGE))).toEqual({ active: 2, drafts: 2, retired: 1 });
  });

  it("reads the schema name out of its page's route", () => {
    expect(schemaNameFromRoute("/admin/schemas/study")).toBe("study");
    expect(schemaNameFromRoute("/admin/schemas/my%20study/")).toBe("my study");
    expect(schemaNameFromRoute("")).toBe("");
  });

  it("reads a version's page as its schema's name, then its own", () => {
    expect(schemaNameFromRoute("/admin/schemas/study/v2")).toBe("study");
    expect(versionNameFromRoute("/admin/schemas/study/v2")).toBe("v2");
    expect(versionNameFromRoute("/admin/schemas/study")).toBeUndefined();
  });

  it("reads a path in the repository the same way", () => {
    expect(schemaNameFromRoute("/Schemas/study")).toBe("study");
    expect(versionNameFromRoute("/Schemas/study/v2")).toBe("v2");
  });
});
