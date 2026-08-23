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
  childrenOf,
  findNode,
  flattenForParentPicker,
  hasDuplicateLabel,
  isDescendantPath,
  parseCategoryTree,
} from "@iap/categories/categoryModel";

// A trimmed-down /Categories.deep.simple.json response: two top-level categories, one nested leaf with
// an inlined (dereferenced) schema version, a retired category, and non-category entries that
// must be ignored.
const homepageJson = {
  "jcr:primaryType": "cat:CategoriesHomepage",
  "jcr:createdBy": "admin",
  "link:links": { "jcr:primaryType": "link:Links" },
  "Retrospective": {
    "jcr:primaryType": "cat:Category",
    "label": "Retrospective studies",
    "description": "Existing data or specimens only.",
    "RetrospectiveData": {
      "jcr:primaryType": "cat:Category",
      "label": "Retrospective Data Studies",
      "schemaVersion": {
        "jcr:primaryType": "sch:SchemaVersion",
        "jcr:uuid": "uuid-sv1",
        "version": "1.0",
        "@path": "/Schemas/basic/1.0",
      },
    },
    "Unnamed": {
      "jcr:primaryType": "cat:Category",
    },
  },
  "Paper": {
    "jcr:primaryType": "cat:Category",
    "label": "Paper submissions",
    "tags": ["retired"],
    // The raw UUID string left when the dereference processor is disabled must not break parsing
    "schemaVersion": "uuid-raw",
  },
};

describe("parseCategoryTree", () => {
  it("parses nested categories in their stored order, skipping non-category entries", () => {
    const tree = parseCategoryTree(homepageJson);

    expect(tree.map(node => node.name)).toEqual(["Retrospective", "Paper"]);
    expect(tree[0].children.map(node => node.name)).toEqual(["RetrospectiveData", "Unnamed"]);
    expect(tree[0].path).toBe("/Categories/Retrospective");
    expect(tree[0].children[0].path).toBe("/Categories/Retrospective/RetrospectiveData");
    expect(tree[0].description).toBe("Existing data or specimens only.");
  });

  it("reads retirement from the tag placed on the category", () => {
    const tree = parseCategoryTree(homepageJson);

    expect(tree[0].retired).toBe(false);
    expect(tree[1].retired).toBe(true);
    expect(tree[1].retiredHere).toBe(true);
  });

  it("reads a retirement inherited from an ancestor, and keeps it apart from an own one", () => {
    // What the repository materializes onto every node below the category the tag was placed on
    const tree = parseCategoryTree({
      "jcr:primaryType": "cat:CategoriesHomepage",
      "Legacy": {
        "jcr:primaryType": "cat:Category",
        "label": "Legacy studies",
        "tags": ["retired"],
        "LegacyData": {
          "jcr:primaryType": "cat:Category",
          "label": "Legacy Data Studies",
          "inheritedTags": ["retired"],
        },
      },
    });

    const child = tree[0].children[0];
    expect(child.retired).toBe(true);
    // Not the child's to lift: only the ancestor carrying the tag can be unretired
    expect(child.retiredHere).toBe(false);
  });

  it("is not retired by an unrelated tag", () => {
    const tree = parseCategoryTree({
      "jcr:primaryType": "cat:CategoriesHomepage",
      "Draft": {
        "jcr:primaryType": "cat:Category",
        "label": "Draft",
        "tags": ["sensitive"],
        "inheritedTags": ["sensitive"],
      },
    });

    expect(tree[0].retired).toBe(false);
    expect(tree[0].retiredHere).toBe(false);
  });

  it("falls back to the node name when a category has no label", () => {
    const tree = parseCategoryTree(homepageJson);

    expect(tree[0].children[1].label).toBe("Unnamed");
  });

  it("parses an inlined schema version, deriving the schema name from its path", () => {
    const tree = parseCategoryTree(homepageJson);

    expect(tree[0].children[0].schemaVersion).toEqual({
      uuid: "uuid-sv1",
      path: "/Schemas/basic/1.0",
      schemaName: "basic",
      version: "1.0",
    });
  });

  it("ignores a schema version that is not inlined", () => {
    const tree = parseCategoryTree(homepageJson);

    expect(tree[1].schemaVersion).toBeUndefined();
  });
});

describe("isDescendantPath", () => {
  it("recognizes strict descendants only", () => {
    expect(isDescendantPath("/Categories/A", "/Categories/A/B")).toBe(true);
    expect(isDescendantPath("/Categories/A", "/Categories/A/B/C")).toBe(true);
    expect(isDescendantPath("/Categories/A", "/Categories/A")).toBe(false);
    // A sibling whose name shares a prefix is not a descendant
    expect(isDescendantPath("/Categories/A", "/Categories/AB")).toBe(false);
  });
});

describe("flattenForParentPicker", () => {
  it("flattens the tree depth-first with depths, excluding the edited node and its subtree", () => {
    const tree = parseCategoryTree(homepageJson);

    const all = flattenForParentPicker(tree);
    expect(all.map(option => [option.label, option.depth])).toEqual([
      ["Retrospective studies", 0],
      ["Retrospective Data Studies", 1],
      ["Unnamed", 1],
      ["Paper submissions", 0],
    ]);

    const excluded = flattenForParentPicker(tree, "/Categories/Retrospective");
    expect(excluded.map(option => option.label)).toEqual(["Paper submissions"]);
  });
});

describe("findNode and childrenOf", () => {
  it("finds nodes anywhere in the tree", () => {
    const tree = parseCategoryTree(homepageJson);

    expect(findNode(tree, "/Categories/Retrospective/RetrospectiveData")?.label)
      .toBe("Retrospective Data Studies");
    expect(findNode(tree, "/Categories/Nowhere")).toBeUndefined();
  });

  it("lists the children of the root and of any category", () => {
    const tree = parseCategoryTree(homepageJson);

    expect(childrenOf(tree, "/Categories").map(node => node.name)).toEqual(["Retrospective", "Paper"]);
    expect(childrenOf(tree, "/Categories/Retrospective").map(node => node.name))
      .toEqual(["RetrospectiveData", "Unnamed"]);
    expect(childrenOf(tree, "/Categories/Nowhere")).toEqual([]);
  });
});

describe("hasDuplicateLabel", () => {
  it("detects duplicates case-insensitively, ignoring the edited node itself", () => {
    const siblings = parseCategoryTree(homepageJson);

    expect(hasDuplicateLabel(siblings, "paper SUBMISSIONS")).toBe(true);
    expect(hasDuplicateLabel(siblings, "paper SUBMISSIONS", "/Categories/Paper")).toBe(false);
    expect(hasDuplicateLabel(siblings, "Something new")).toBe(false);
  });
});
