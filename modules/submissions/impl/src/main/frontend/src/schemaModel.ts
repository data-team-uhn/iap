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

// Reading the schemas a submitter may choose from out of a `/Schemas` listing. No React, no fetch.

/**
 * The listing of the schemas and their versions. Depth 2 reaches the versions. `simple` leaves out
 * the repository's bookkeeping, each version's requirements, and the workflow version each version
 * would inline.
 */
export const SCHEMAS_URL = "/Schemas.2.simple.json";

/**
 * One serialized node: its own properties, plus its children under their node names. A child node
 * carries the `@path` and `@name` keys, which tell it apart from a property value.
 */
export type JsonNode = Record<string, unknown>;

/**
 * What the submitter picks: a schema open for submissions, with the version a submission against it
 * answers.
 */
export interface SchemaChoice {
  /** The active version's path, which raising a submission takes as its `schemaVersion`. */
  path: string;
  /** The schema's title, or its node name when the title is empty or missing. */
  title: string;
  /**
   * The version's label, such as 1.0. Two submissions against the same schema can answer different
   * versions of it.
   */
  version: string;
  /** The version's description, if it has one. */
  description?: string;
}

const SCHEMA_PRIMARY_TYPE = "sch:Schema";

const SCHEMA_VERSION_PRIMARY_TYPE = "sch:SchemaVersion";

// The child nodes of the given primary type. The homepage and a schema both accept any child node,
// so not every child is a schema or a version.
function childNodes(node: JsonNode, primaryType: string): JsonNode[] {
  return Object.values(node)
    .filter((value): value is JsonNode =>
      typeof value === "object" && value !== null
        && typeof (value as JsonNode)["@path"] === "string"
        && (value as JsonNode)["jcr:primaryType"] === primaryType);
}

// Whether a tag, such as a lifecycle state, is placed on the node itself
function hasTag(node: JsonNode, tag: string): boolean {
  const tags = node.tags;
  return Array.isArray(tags) && tags.includes(tag);
}

// A string property; undefined when it is missing or empty. Oak accepts an empty value for a
// mandatory property such as `title`.
function text(node: JsonNode, key: string): string | undefined {
  const value = node[key];
  return typeof value === "string" && value.length > 0 ? value : undefined;
}

/**
 * The schemas a submission may be raised against: each schema not retired, paired with its active
 * version. A schema with no active version is left out.
 *
 * The serialization leaves out retired schemas and inactive versions by default. The `-active`
 * selector turns that off, so the tags are checked here as well.
 *
 * @param tree a `/Schemas` listing, serialized to the depth `SCHEMAS_URL` asks for
 * @returns what may be picked, in the order the schemas were served
 */
export function schemaChoices(tree: JsonNode): SchemaChoice[] {
  return childNodes(tree, SCHEMA_PRIMARY_TYPE)
    .filter(schema => !hasTag(schema, "retired"))
    .flatMap(schema => {
      const version = childNodes(schema, SCHEMA_VERSION_PRIMARY_TYPE)
        .find(candidate => hasTag(candidate, "active"));
      if (!version) {
        return [];
      }
      return [ {
        path: version["@path"] as string,
        title: text(schema, "title") ?? text(schema, "@name") ?? "",
        version: text(version, "version") ?? "",
        description: text(version, "description"),
      } ];
    });
}

/** The category tree, deep, with each category's schema version inlined by the dereference processor. */
export const CATEGORIES_URL = "/Categories.deep.simple.json";

const CATEGORY_PRIMARY_TYPE = "cat:Category";

// The category tag that closes it to new submissions
const RETIRED_TAG = "retired";

function isRetired(category: JsonNode): boolean {
  const tags = category.tags;
  return Array.isArray(tags) && tags.includes(RETIRED_TAG);
}

// The path of the schema a version belongs to: /Schemas/<schema>/<version> gives /Schemas/<schema>
function getSchemaPath(versionPath: string): string {
  return versionPath.substring(0, versionPath.lastIndexOf("/"));
}

/**
 * The top categories a submission may be raised under, each offering its schema's active version. A top
 * category names the schema its submissions answer; the categories below it are picked later, from the
 * uploaded document. A category whose schema is not open, or that is retired, is not offered.
 *
 * @param categories a `/Categories` listing, serialized as `CATEGORIES_URL` asks for
 * @param schemas what `schemaChoices` offers, which says which schemas are open and at which version
 * @returns what may be picked, in stored order
 */
export function categoryChoices(categories: JsonNode, schemas: SchemaChoice[]): SchemaChoice[] {
  const open = new Map(schemas.map(choice => [ getSchemaPath(choice.path), choice ]));
  return Object.values(categories)
    .filter((value): value is JsonNode => typeof value === "object" && value !== null
      && (value as JsonNode)["jcr:primaryType"] === CATEGORY_PRIMARY_TYPE)
    .filter(category => !isRetired(category))
    .flatMap(category => {
      const version = category.schemaVersion;
      const versionPath = typeof version === "object" && version !== null
        ? (version as JsonNode)["@path"]
        : undefined;
      const schema = typeof versionPath === "string" ? open.get(getSchemaPath(versionPath)) : undefined;
      if (!schema) {
        return [];
      }
      return [ {
        path: schema.path,
        // A category is picked by what it is, so the schema's version label is not shown after it
        version: "",
        title: text(category, "label") ?? text(category, "@name") ?? schema.title,
        description: text(category, "description") ?? schema.description,
      } ];
    });
}

/**
 * What the new-submission dialog offers: the top categories, then every open schema no category offers.
 *
 * @param categories a `/Categories` listing, serialized as `CATEGORIES_URL` asks for
 * @param schemas a `/Schemas` listing, serialized as `SCHEMAS_URL` asks for
 * @returns what may be picked
 */
export function submissionChoices(categories: JsonNode, schemas: JsonNode): SchemaChoice[] {
  const open = schemaChoices(schemas);
  const byCategory = categoryChoices(categories, open);
  const offered = new Set(byCategory.map(choice => choice.path));
  return [ ...byCategory, ...open.filter(choice => !offered.has(choice.path)) ];
}
