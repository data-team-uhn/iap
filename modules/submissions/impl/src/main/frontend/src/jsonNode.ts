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

// Reading a serialized repository node, where a node's properties and its children are both plain
// keys of the same object and only the shape tells them apart.

// A serialized JCR node: its properties, plus its children as nested objects.
export type JsonNode = Record<string, unknown>;

export function isNode(value: unknown): value is JsonNode {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

// The children of a serialized node having the given resource type, in their storage order.
export function childrenOfType(node: JsonNode, resourceType: string): JsonNode[] {
  return Object.values(node)
    .filter(isNode)
    .filter(child => child["sling:resourceType"] === resourceType);
}
