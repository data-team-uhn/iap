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

// The category tree as the administrative UI works with it: parsed out of the JSON of /Categories,
// with the computations on it that the UI needs. Nothing here renders or fetches.

// The JSON serialization of a repository node, with each child node as a nested record.
export type JcrNode = Record<string, unknown>;

// The schema version a category is bound to, as the default `dereference` JSON processor inlines it
// in the category's JSON.
export interface SchemaVersionRef {
  // The `jcr:uuid` of the schema version, which the category's `schemaVersion` property stores.
  uuid?: string;
  // The path of the schema version, such as `/Schemas/basic/v1`.
  path?: string;
  // The node name of the schema this version belongs to, taken from the path.
  schemaName?: string;
  // The version label, such as "1.0".
  version?: string;
}

// One category in the tree, with its children.
export interface CategoryNode {
  // The node name, the last segment of the path.
  name: string;
  // The absolute path.
  path: string;
  // The name submitters see; the node name if the category has no label.
  label: string;
  // What belongs in this category.
  description?: string;
  // Whether the category is closed to new submissions, by its own retirement or an ancestor's.
  retired: boolean;
  // Whether the category carries the retirement itself, rather than through an ancestor. Only that
  // category can have it lifted.
  retiredHere: boolean;
  // The schema version bound to this category, if any.
  schemaVersion?: SchemaVersionRef;
  // The subcategories, in their stored order.
  children: CategoryNode[];
}

const CATEGORY_PRIMARY_TYPE = "cat:Category";

// The tag that closes a category to new submissions. A category's own tags are in `tags`. The tag is
// inheritable, and the tags module copies it into `inheritedTags` on every node below its carrier.
const RETIRED_TAG = "retired";

const OWN_TAGS = "tags";

const INHERITED_TAGS = "inheritedTags";

const isCategory = (value: unknown): value is JcrNode =>
  typeof value === "object" && value !== null
    && (value as JcrNode)["jcr:primaryType"] === CATEGORY_PRIMARY_TYPE;

const hasTag = (node: JcrNode, property: string, tag: string): boolean => {
  const tags = node[property];
  return Array.isArray(tags) && tags.includes(tag);
};

const parseSchemaVersion = (value: unknown): SchemaVersionRef | undefined => {
  // The `dereference` processor serializes a REFERENCE as the node it points at. Anything else,
  // such as the raw `jcr:uuid` when dereferencing is off, is ignored.
  if (typeof value !== "object" || value === null) {
    return undefined;
  }
  const node = value as JcrNode;
  const path = node["@path"] as string | undefined;
  // The schema's own name is the next-to-last path segment: /Schemas/<schema>/<version>
  const schemaName = path?.split("/").at(-2);
  return {
    uuid: node["jcr:uuid"] as string | undefined,
    path,
    schemaName,
    version: node.version as string | undefined,
  };
};

const parseCategory = (name: string, node: JcrNode, parentPath: string): CategoryNode => {
  const path = `${parentPath}/${name}`;
  const retiredHere = hasTag(node, OWN_TAGS, RETIRED_TAG);
  return {
    name,
    path,
    label: (node.label as string | undefined) ?? name,
    description: node.description as string | undefined,
    retired: retiredHere || hasTag(node, INHERITED_TAGS, RETIRED_TAG),
    retiredHere,
    schemaVersion: parseSchemaVersion(node.schemaVersion),
    children: parseCategoryChildren(node, path),
  };
};

const parseCategoryChildren = (node: JcrNode, path: string): CategoryNode[] =>
  Object.entries(node)
    .filter(([, value]) => isCategory(value))
    .map(([name, value]) => parseCategory(name, value as JcrNode, path));

// Parses the category tree out of the deep JSON of the /Categories homepage. The children keep the
// order the JSON lists them in, which is their stored order. The exception is a node name that looks
// like an integer: JavaScript lists such keys first, in numeric order.
export const parseCategoryTree = (homepage: JcrNode, path = "/Categories"): CategoryNode[] =>
  parseCategoryChildren(homepage, path);

// Whether `path` is a strict descendant of `ancestorPath`.
export const isDescendantPath = (ancestorPath: string, path: string): boolean =>
  path.startsWith(`${ancestorPath}/`);

// One entry of a flattened tree: the category and how deep it nests (0 = top-level).
export interface FlattenedCategory {
  node: CategoryNode;
  depth: number;
}

// Flattens the tree depth first, so the entries read in tree order, and the depth sets the
// indentation. `excludePath`, when given, leaves out that category and its whole subtree, so that a
// category cannot be moved under itself.
export const flattenTree = (roots: CategoryNode[], excludePath?: string): FlattenedCategory[] => {
  const result: FlattenedCategory[] = [];
  const visit = (nodes: CategoryNode[], depth: number) => {
    for (const node of nodes) {
      if (excludePath && (node.path === excludePath || isDescendantPath(excludePath, node.path))) {
        continue;
      }
      result.push({ node, depth });
      visit(node.children, depth + 1);
    }
  };
  visit(roots, 0);
  return result;
};

// One selectable entry of the "parent category" picker.
export interface ParentOption {
  path: string;
  label: string;
  // Nesting depth, for indenting the option list (0 = top-level category).
  depth: number;
}

// The tree as the list of possible parents for a category, leaving out `excludePath` and its subtree.
export const flattenForParentPicker = (roots: CategoryNode[], excludePath?: string): ParentOption[] =>
  flattenTree(roots, excludePath)
    .map(({ node, depth }) => ({ path: node.path, label: node.label, depth }));

// The category with the given path anywhere in the tree; undefined if there is none.
export const findNode = (roots: CategoryNode[], path: string): CategoryNode | undefined => {
  for (const node of roots) {
    if (node.path === path) {
      return node;
    }
    const found = findNode(node.children, path);
    if (found) {
      return found;
    }
  }
  return undefined;
};

// The children of the given parent: the top-level categories for the tree's root, and empty if the
// parent is not in the tree.
export const childrenOf = (roots: CategoryNode[], parentPath: string, rootPath = "/Categories"): CategoryNode[] =>
  parentPath === rootPath ? roots : findNode(roots, parentPath)?.children ?? [];

// Whether another of the siblings has the same label, ignoring case and surrounding spaces.
// `exceptPath` is the category being edited, which is not compared with itself.
export const hasDuplicateLabel = (siblings: CategoryNode[], label: string, exceptPath?: string): boolean => {
  const normalized = label.trim().toLowerCase();
  return siblings.some(sibling => sibling.path !== exceptPath
    && sibling.label.trim().toLowerCase() === normalized);
};
