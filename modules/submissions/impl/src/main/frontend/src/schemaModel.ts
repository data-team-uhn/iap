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
