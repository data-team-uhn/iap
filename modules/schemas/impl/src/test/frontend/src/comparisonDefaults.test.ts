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

import { defaultBase, sourceOf } from "@iap/schemas/comparisonDefaults";

const version = (name: string, created: string, tags: string[] = [ "draft" ]) => ({
  "@name": name, "@path": `/Schemas/trial/${name}`, "jcr:uuid": `uuid-${name}`, "jcr:created": created, tags,
});
const V1 = version("v1", "2026-09-01T10:00:00.000-04:00", [ "retired" ]);
const V2 = version("v2", "2026-09-10T10:00:00.000-04:00", [ "active" ]);
const V3 = version("v3", "2026-09-20T10:00:00.000-04:00");
const V4 = version("v4", "2026-09-25T10:00:00.000-04:00");
const VERSIONS = [ V1, V2, V3, V4 ];
const RULES = [ "active", "source", "previous" ];
const copiedFrom = (reference: unknown, type: unknown = "/LinkTypes/copiedFrom") =>
  ({ "f9125508": { "jcr:primaryType": "link:WeakLink", type, reference } });

describe("sourceOf", () => {
  it("finds the version a version was copied from, however its link refers to it", () => {
    expect(sourceOf(copiedFrom("/Schemas/trial/v1"), VERSIONS)).toBe(V1);
    expect(sourceOf(copiedFrom("uuid-v3"), VERSIONS)).toBe(V3);
    expect(sourceOf(copiedFrom({ "@path": "/Schemas/trial/v2" }, { "@path": "/LinkTypes/copiedFrom" }), VERSIONS))
      .toBe(V2);
  });

  it("finds none without such a link, or one to elsewhere", () => {
    expect(sourceOf(undefined, VERSIONS)).toBeUndefined();
    expect(sourceOf(copiedFrom("/Schemas/trial/v1", "/LinkTypes/references"), VERSIONS)).toBeUndefined();
    expect(sourceOf(copiedFrom("/Schemas/other/v1"), VERSIONS)).toBeUndefined();
  });
});

describe("defaultBase", () => {
  it("compares a version with the active one first", () => {
    expect(defaultBase(V4, VERSIONS, RULES, V3)).toBe(V2);
  });

  it("goes on to the next rule when one finds nothing, or only the version itself", () => {
    expect(defaultBase(V2, VERSIONS, RULES, V1)).toBe(V1);
    expect(defaultBase(V2, VERSIONS, RULES)).toBe(V1);
    expect(defaultBase(V4, VERSIONS, [ "source", "previous" ])).toBe(V3);
  });

  it("follows the rules in the order given, ignoring names it does not know", () => {
    expect(defaultBase(V4, VERSIONS, [ "previous", "active" ])).toBe(V3);
    expect(defaultBase(V4, VERSIONS, [ "newest", "toString", "active" ])).toBe(V2);
  });

  it("finds none where no rule finds another version", () => {
    expect(defaultBase(V1, VERSIONS, [ "previous" ])).toBeUndefined();
    expect(defaultBase(V1, [ V1 ], RULES)).toBeUndefined();
    expect(defaultBase(V4, VERSIONS, [])).toBeUndefined();
  });
});
