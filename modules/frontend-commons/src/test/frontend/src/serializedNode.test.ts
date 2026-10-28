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

import { childrenOf, childrenOfType, isNode, lastSegmentOf, stringsOf } from "@iap/frontend-commons/serializedNode";

describe("isNode", () => {
  it("tells a node from a value or a list of values", () => {
    expect(isNode({ "jcr:primaryType": "nt:unstructured" })).toBe(true);
    expect([ null, "text", 3, [ {} ] ].some(isNode)).toBe(false);
  });
});

describe("childrenOf", () => {
  const node = { "title": "Scale", "2": { "@name": "2" }, "10": { "@name": "10" }, "low": { "@name": "low" },
    "values": [ { "@name": "listed" } ] };

  it("lists the children in the order the node keeps them", () => {
    expect(childrenOf({ ...node, "@order": [ "low", "10", "2" ] }).map(child => child["@name"]))
      .toEqual([ "low", "10", "2" ]);
  });

  it("leaves out a name the serialization gives no node for", () => {
    expect(childrenOf({ ...node, "@order": [ "gone", "2" ] }).map(child => child["@name"])).toEqual([ "2" ]);
  });

  it("lists them as their keys come when the node says no order", () => {
    // Whole numbers first, whatever order they were written in
    expect(childrenOf(node).map(child => child["@name"])).toEqual([ "2", "10", "low" ]);
  });
});

describe("childrenOfType", () => {
  it("keeps the children of one resource type, in the node's order", () => {
    const node = { "@order": [ "b", "a", "c" ],
      "a": { "@name": "a", "sling:resourceType": "x/Task" },
      "b": { "@name": "b", "sling:resourceType": "x/Task" },
      "c": { "@name": "c", "sling:resourceType": "x/Token" } };

    expect(childrenOfType(node, "x/Task").map(child => child["@name"])).toEqual([ "b", "a" ]);
  });
});

describe("stringsOf", () => {
  it("reads a list, a bare single value, and nothing else", () => {
    expect(stringsOf([ "a", 3, "b" ])).toEqual([ "a", "b" ]);
    expect(stringsOf("a")).toEqual([ "a" ]);
    expect(stringsOf(undefined)).toEqual([]);
    expect(stringsOf(3)).toEqual([]);
  });
});

describe("lastSegmentOf", () => {
  it("gives the name a path ends with", () => {
    expect(lastSegmentOf("/Submissions/ab/request")).toBe("request");
    expect(lastSegmentOf("request")).toBe("request");
  });
});
