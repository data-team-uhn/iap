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

// A node as the JSON serialization gives it: its properties and its children, by name
export type SerializedNode = Record<string, unknown>;

// Whether a serialized value is a node, rather than a value or a list of values
export const isNode = (value: unknown): value is SerializedNode =>
  typeof value === "object" && value !== null && !Array.isArray(value);

// A node's children, in the order of its `@order`, which the `order` JSON processor adds, or else in
// the order of its keys. Key order is not the stored order: a JavaScript object lists the keys that
// look like whole numbers first, in numeric order.
export function childrenOf(node: SerializedNode): SerializedNode[] {
  const order = node["@order"];
  return Array.isArray(order)
    ? order.map(name => node[String(name)]).filter(isNode)
    : Object.values(node).filter(isNode);
}

// A node's children of one resource type, in the order childrenOf gives
export const childrenOfType = (node: SerializedNode, resourceType: string): SerializedNode[] =>
  childrenOf(node).filter(child => child["sling:resourceType"] === resourceType);

// A property's values as an array, one for a single value
const valuesOf = (value: unknown): unknown[] => (Array.isArray(value) ? value : [ value ]);

// The strings a property holds, as an array: one string for a single value, and empty for no value.
// A value that is not a string is left out.
export function stringsOf(value: unknown): string[] {
  return valuesOf(value).filter((entry): entry is string => typeof entry === "string");
}

// The strings and numbers a property holds, as text: "3" for the number 3. Any other value is left out.
export const textValuesOf = (value: unknown): string[] => valuesOf(value)
  .filter((entry): entry is string | number => typeof entry === "string" || typeof entry === "number")
  .map(String);

// A node's string property; undefined if it is missing, not a string, or only whitespace
export function textOf(node: SerializedNode, key: string): string | undefined {
  const value = node[key];
  return typeof value === "string" && value.trim() !== "" ? value : undefined;
}

// The last segment of a path, which is the name of the node it leads to
export const lastSegmentOf = (path: string): string => path.slice(path.lastIndexOf("/") + 1);
