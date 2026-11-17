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
  childrenOfPrimaryType,
  isTagged,
  type SerializedNode,
  textOf,
} from "@iap/frontend-commons/serializedNode";

// Reading the schemas a submitter may choose from out of a `/Schemas` listing. No React, no fetch.

/**
 * The listing of the schemas and their versions. Depth 2 reaches the versions. `simple` leaves out
 * the repository's bookkeeping, each version's requirements, and the workflow version each version
 * would inline.
 */
export const SCHEMAS_URL = "/Schemas.2.simple.json";

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

const ACTIVE_TAG = "active";

const RETIRED_TAG = "retired";

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
export function schemaChoices(tree: SerializedNode): SchemaChoice[] {
  return childrenOfPrimaryType(tree, SCHEMA_PRIMARY_TYPE)
    .filter(schema => !isTagged(schema, RETIRED_TAG))
    .flatMap(schema => {
      const version = childrenOfPrimaryType(schema, SCHEMA_VERSION_PRIMARY_TYPE)
        .find(candidate => isTagged(candidate, ACTIVE_TAG));
      if (!version) {
        return [];
      }
      return [ {
        path: version["@path"] as string,
        title: textOf(schema, "title") ?? textOf(schema, "@name") ?? "",
        version: textOf(version, "version") ?? "",
        description: textOf(version, "description"),
      } ];
    });
}

/** The category tree, deep, with each category's schema version inlined by the dereference processor. */
export const CATEGORIES_URL = "/Categories.deep.simple.json";

const CATEGORY_PRIMARY_TYPE = "cat:Category";

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
export function categoryChoices(categories: SerializedNode, schemas: SchemaChoice[]): SchemaChoice[] {
  const open = new Map(schemas.map(choice => [ getSchemaPath(choice.path), choice ]));
  return childrenOfPrimaryType(categories, CATEGORY_PRIMARY_TYPE)
    .filter(category => !isTagged(category, RETIRED_TAG))
    .flatMap(category => {
      const version = category.schemaVersion;
      const versionPath = typeof version === "object" && version !== null
        ? (version as SerializedNode)["@path"]
        : undefined;
      const schema = typeof versionPath === "string" ? open.get(getSchemaPath(versionPath)) : undefined;
      if (!schema) {
        return [];
      }
      return [ {
        path: schema.path,
        // A category is picked by what it is, so the schema's version label is not shown after it
        version: "",
        title: textOf(category, "label") ?? textOf(category, "@name") ?? schema.title,
        description: textOf(category, "description") ?? schema.description,
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
export function submissionChoices(categories: SerializedNode, schemas: SerializedNode): SchemaChoice[] {
  const open = schemaChoices(schemas);
  const byCategory = categoryChoices(categories, open);
  const offered = new Set(byCategory.map(choice => choice.path));
  return [ ...byCategory, ...open.filter(choice => !offered.has(choice.path)) ];
}
