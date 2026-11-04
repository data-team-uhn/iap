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

import { candidateOf, referenceQuery } from "@iap/frontend-commons/inputs/referenceTargets";

describe("referenceQuery", () => {
  it("looks for the nodes of a resource type under a root", () => {
    expect(referenceQuery({ resourceType: "wf/Version", root: "/Workflows/" }))
      .toBe("select * from [nt:base] as n where n.[sling:resourceType] = 'wf/Version'"
        + " and isdescendantnode(n, '/Workflows')");
    expect(referenceQuery({ resourceType: "o'dd", root: "/" }))
      .toBe("select * from [nt:base] as n where n.[sling:resourceType] = 'o''dd' and isdescendantnode(n, '/')");
    expect(referenceQuery({ resourceType: "t" })).toBe("select * from [nt:base] as n where n.[sling:resourceType] = 't'");
    expect(referenceQuery({ resourceType: "t", nodeType: "" }))
      .toBe("select * from [nt:base] as n where n.[sling:resourceType] = 't'");
  });

  it("looks for the nodes of a node type, which takes in its subtypes and mixins", () => {
    expect(referenceQuery({ nodeType: "sch:Schema" })).toBe("select * from [sch:Schema] as n");
    expect(referenceQuery({ nodeType: "sch:Schema", root: "/Sch'emas" }))
      .toBe("select * from [sch:Schema] as n where isdescendantnode(n, '/Sch''emas')");
    expect(referenceQuery({ nodeType: "sch:Schema", resourceType: "sch/Schema" }))
      .toBe("select * from [sch:Schema] as n where n.[sling:resourceType] = 'sch/Schema'");
  });

  it("looks for nothing without a type, or with a node type no node can have", () => {
    expect(referenceQuery({})).toBeUndefined();
    expect(referenceQuery({ root: "/Workflows", nodeType: "" })).toBeUndefined();
    expect(referenceQuery({ nodeType: "nt:base] as n, [x" })).toBeUndefined();
    expect(referenceQuery({ nodeType: "[x" })).toBeUndefined();
  });
});

describe("candidateOf", () => {
  it("offers a node by its name, or else by where it is", () => {
    expect(candidateOf({ "@path": "/Workflows/fast/v2", "title": "Fast track" }, "/Workflows"))
      .toEqual({ path: "/Workflows/fast/v2", label: "Fast track" });
    expect(candidateOf({ "@path": "/Workflows/fast/v2", "title": " ", "label": "Fast" }, "/Workflows"))
      .toEqual({ path: "/Workflows/fast/v2", label: "Fast" });
    expect(candidateOf({ "@path": "/Workflows/review/v1" }, "/Workflows/"))
      .toEqual({ path: "/Workflows/review/v1", label: "review/v1" });
    expect(candidateOf({ "@path": "/Elsewhere/v1" }, "/Workflows")).toEqual({ path: "/Elsewhere/v1", label: "/Elsewhere/v1" });
    expect(candidateOf({ "@path": "/Elsewhere/v1" })).toEqual({ path: "/Elsewhere/v1", label: "/Elsewhere/v1" });
    expect(candidateOf({ title: "No path" })).toBeUndefined();
    expect(candidateOf({ "@path": "/Workflows/fast/v2", "jcr:uuid": "uuid-fast", "title": "Fast track" }))
      .toEqual({ path: "/Workflows/fast/v2", label: "Fast track", identifier: "uuid-fast" });
  });
});
