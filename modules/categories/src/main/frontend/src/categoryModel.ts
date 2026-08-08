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

// The pure, testable model behind the category manager: parsing the category tree out of the
// repository's JSON serialization, and the tree computations the UI needs (parent picking,
// descendant checks, duplicate label detection). No React, no fetch.

// The raw JSON serialization of a repository node: an open string-keyed record where child nodes
// appear as nested records.
export type JcrNode = Record<string, unknown>;

// The schema version a category is bound to, as inlined in the category's serialization by the
// default `dereference` JSON processor.
export interface SchemaVersionRef {
  // The identifier stored in the category's `schemaVersion` REFERENCE property.
  uuid?: string;
  // The path of the schema version node, e.g. `/Schemas/basic/1.0`.
  path?: string;
  // The name of the schema this version belongs to, derived from the path.
  schemaName?: string;
  // The version label, e.g. "1.0".
  version?: string;
}

// One category in the tree, with its children.
export interface CategoryNode {
  // The technical name, the last segment of the path.
  name: string;
  // The absolute repository path.
  path: string;
  // The human-readable name displayed to submitters; falls back to the technical name.
  label: string;
  // The prompt-ready description of what belongs in this category.
  description?: string;
  // Whether the category is retired: no new submissions may be filed under it or its children.
  retired: boolean;
  // The schema version bound to this (leaf) category, if any.
  schemaVersion?: SchemaVersionRef;
  // The subcategories, in their stored order.
  children: CategoryNode[];
}

const CATEGORY_PRIMARY_TYPE = "cat:Category";

const isCategory = (value: unknown): value is JcrNode =>
  typeof value === "object" && value !== null
    && (value as JcrNode)["jcr:primaryType"] === CATEGORY_PRIMARY_TYPE;

const parseSchemaVersion = (value: unknown): SchemaVersionRef | undefined => {
  // With the default `dereference` processor active, a REFERENCE property serializes as the
  // referenced node itself; anything else (e.g. a raw UUID string when dereferencing is off, or
  // an unresolvable reference) is ignored.
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
  return {
    name,
    path,
    label: (node.label as string | undefined) ?? name,
    description: node.description as string | undefined,
    retired: node.retired === true,
    schemaVersion: parseSchemaVersion(node.schemaVersion),
    children: parseCategoryChildren(node, path),
  };
};

const parseCategoryChildren = (node: JcrNode, path: string): CategoryNode[] =>
  Object.entries(node)
    .filter(([, value]) => isCategory(value))
    .map(([name, value]) => parseCategory(name, value as JcrNode, path));

// Parses the category tree out of the deep JSON serialization of the /Categories homepage.
// Children appear in the JSON in their stored (admin-arranged) order, which is preserved.
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

// Flattens the tree depth-first, so the entries read like the tree; the depth drives indentation.
// When `excludePath` is given, that category and its whole subtree are left out (e.g. a category
// cannot become its own parent).
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

// Flattens the tree into the list of possible parents for a category.
export const flattenForParentPicker = (roots: CategoryNode[], excludePath?: string): ParentOption[] =>
  flattenTree(roots, excludePath)
    .map(({ node, depth }) => ({ path: node.path, label: node.label, depth }));

// Finds the node with the given path anywhere in the tree, or undefined.
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

// The children of the given parent path: the roots themselves for the tree root, otherwise the
// children of the matching node (empty if the parent cannot be found).
export const childrenOf = (roots: CategoryNode[], parentPath: string, rootPath = "/Categories"): CategoryNode[] =>
  parentPath === rootPath ? roots : findNode(roots, parentPath)?.children ?? [];

// Whether another category with the same label (case-insensitive) already exists among the
// prospective siblings; `exceptPath` skips the category being edited itself.
export const hasDuplicateLabel = (siblings: CategoryNode[], label: string, exceptPath?: string): boolean => {
  const normalized = label.trim().toLowerCase();
  return siblings.some(sibling => sibling.path !== exceptPath
    && sibling.label.trim().toLowerCase() === normalized);
};
