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

// What a schema listing says, and how to read a submitter's choices out of it. No React, no fetch:
// everything here is a pure function of its arguments. The I/O that uses it is in useSchemas.

/**
 * What is read, and how deep. Depth 2 reaches the schemas and their versions, and `simple` drops
 * what a picker never shows: every version's requirement subtree, and the `mix:versionable`
 * bookkeeping each node repeats.
 */
export const SCHEMAS_URL = "/Schemas.2.simple.json";

/**
 * One serialized node: its own properties, plus its children under their node names. The
 * `@path`/`@name` keys are what the serializer's identification step adds, and what tells a child
 * node apart from an ordinary property value.
 */
export type JsonNode = Record<string, unknown>;

/**
 * What the submitter picks: a schema that is open for submissions, together with the version their
 * submission will actually answer.
 */
export interface SchemaChoice {
  /** The active version's path, which is what raising a submission is asked for. */
  path: string;
  /** The schema's human-readable name. */
  title: string;
  /**
   * The version's own label, shown because two submissions against the same schema can answer
   * different versions of it, and which one applies is not a detail.
   */
  version: string;
  description?: string;
}

const SCHEMA_PRIMARY_TYPE = "sch:Schema";

const SCHEMA_VERSION_PRIMARY_TYPE = "sch:SchemaVersion";

// The children of the given primary type. Both node types end their definition with `+ * (nt:base)`
// for extensibility. So the homepage's children are not all schemas, nor a schema's children all
// versions, and the type is what says which are which.
function childNodes(node: JsonNode, primaryType: string): JsonNode[] {
  return Object.values(node)
    .filter((value): value is JsonNode =>
      typeof value === "object" && value !== null
        && typeof (value as JsonNode)["@path"] === "string"
        && (value as JsonNode)["jcr:primaryType"] === primaryType);
}

// An empty string is not a value here: `title` is mandatory in the CND and Oak still permits "",
// so a caller's `?? fallback` has to fire on it.
function text(node: JsonNode, key: string): string | undefined {
  const value = node[key];
  return typeof value === "string" && value.length > 0 ? value : undefined;
}

/**
 * The schemas a submission may be raised against: those marked active, each paired with its active
 * version. Both halves have to be active: a retired version of a live schema is no more open than a
 * live version of a retired one.
 *
 * The server already leaves retired ones out, so this normally has nothing to do. It is checked
 * again because that filtering is a serialization default and can be switched off per request:
 * reading the flag we were given beats assuming which processors ran.
 *
 * @param tree a `/Schemas` listing, serialized to the depth `SCHEMAS_URL` asks for
 * @returns what may be picked, in the order the schemas were served
 */
export function schemaChoices(tree: JsonNode): SchemaChoice[] {
  return childNodes(tree, SCHEMA_PRIMARY_TYPE)
    .filter(schema => schema.active === true)
    .flatMap(schema => {
      const version = childNodes(schema, SCHEMA_VERSION_PRIMARY_TYPE)
        .find(candidate => candidate.active === true);
      if (!version) {
        return [];
      }
      return [ {
        path: version["@path"] as string,
        // A schema's title is mandatory, so the node name is a fallback for content that predates
        // the constraint rather than an expected case
        title: text(schema, "title") ?? text(schema, "@name") ?? "",
        version: text(version, "version") ?? "",
        description: text(version, "description"),
      } ];
    });
}
