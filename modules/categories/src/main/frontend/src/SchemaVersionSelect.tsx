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

import { useEffect, useState, type ReactNode } from "react";

import { ListSubheader, MenuItem, Stack, TextField } from "@mui/material";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { readJson } from "@iap/frontend-commons/requestFailure";
import TagChip from "@iap/tags/TagChip";

import type { JcrNode } from "./categoryModel";

// One selectable schema version.
interface VersionOption {
  // The `jcr:uuid`, which the category's `schemaVersion` stores
  uuid: string;
  // The version label, such as "1.0"
  version: string;
  // The names of the tags placed on it, its lifecycle state among them
  tags: string[];
}

interface SchemaGroup {
  // The schema's node name, unique among its siblings, which titles are not.
  name: string;
  title: string;
  tags: string[];
  versions: VersionOption[];
}

interface SchemaVersionSelectProps {
  // The jcr:uuid of the currently selected schema version, or "" for none.
  value: string;
  onChange: (uuid: string) => void;
}

const isType = (value: unknown, primaryType: string): value is JcrNode =>
  typeof value === "object" && value !== null && (value as JcrNode)["jcr:primaryType"] === primaryType;

// The tags placed on a node, as serialized: a list of names, absent when there are none.
const tagsOf = (node: JcrNode): string[] =>
  Array.isArray(node.tags) ? node.tags.filter((tag): tag is string => typeof tag === "string") : [];

// Parses the JSON of /Schemas into one group per schema and one option per version. A version
// without a `jcr:uuid` cannot be referenced and is skipped, and so is a schema left with no versions.
const parseSchemas = (homepage: JcrNode): SchemaGroup[] =>
  Object.entries(homepage)
    .filter(([, schema]) => isType(schema, "sch:Schema"))
    .map(([name, schema]) => {
      const node = schema as JcrNode;
      return {
        name,
        title: (node.title as string | undefined) ?? name,
        tags: tagsOf(node),
        versions: Object.values(node)
          .filter(version => isType(version, "sch:SchemaVersion"))
          .flatMap(version => {
            const uuid = version["jcr:uuid"] as string | undefined;
            return uuid ? [{
              uuid,
              version: (version.version as string | undefined) ?? "?",
              tags: tagsOf(version),
            }] : [];
          }),
      };
    })
    .filter(group => group.versions.length > 0);

// A picker for the schema version a category is bound to, listing every version of every schema
// under /Schemas, grouped by schema. "None" leaves the category unbound.
function SchemaVersionSelect({ value, onChange }: SchemaVersionSelectProps) {
  const [ groups, setGroups ] = useState<SchemaGroup[]>([]);
  const [ loading, setLoading ] = useState(true);
  const [ error, setError ] = useState(false);
  const authenticatedFetch = useAuthenticatedFetch();

  useEffect(() => {
    // `simple` cuts each version to its own properties
    authenticatedFetch("/Schemas.deep.simple.json")
      .then(response => response.ok
        ? readJson<JcrNode>(response)
        : Promise.reject(new Error(`Failed to load schemas: ${response.status}`)))
      .then(json => setGroups(parseSchemas(json)))
      .catch((err: unknown) => {
        console.error("Could not load the available schema versions", err);
        setError(true);
      })
      .finally(() => setLoading(false));
  }, [authenticatedFetch]);

  // A Select takes its options as one flat list, subheaders included
  const items: ReactNode[] = [
    <MenuItem key="none" value="">
      <em>None &mdash; default handling</em>
    </MenuItem>,
  ];
  groups.forEach(group => {
    items.push(
      <ListSubheader key={`schema-${group.name}`}>
        <Stack direction="row" sx={{ gap: 1, alignItems: "center" }}>
          <span>{group.title}</span>
          <TagChip tags={group.tags} category="lifecycle" />
        </Stack>
      </ListSubheader>
    );
    group.versions.forEach(version => {
      items.push(
        <MenuItem key={version.uuid} value={version.uuid}>
          <Stack direction="row" sx={{ gap: 1, alignItems: "center" }}>
            <span>v{version.version}</span>
            <TagChip tags={version.tags} category="lifecycle" />
          </Stack>
        </MenuItem>
      );
    });
  });
  // A bound version the list does not hold, such as one whose schema is no longer under /Schemas,
  // stays selectable, so that the Select keeps the value
  if (value && !groups.some(group => group.versions.some(version => version.uuid === value))) {
    items.push(<MenuItem key="current" value={value}>Current binding</MenuItem>);
  }

  return (
    <TextField
      select
      fullWidth
      label="Schema version"
      value={loading ? "" : value}
      disabled={loading || error}
      onChange={event => onChange(event.target.value)}
      helperText={error
        ? "The available schemas could not be loaded"
        : "The schema (and through it the workflow) that submissions in this category will follow"}
      error={error}
    >
      {items}
    </TextField>
  );
}

export default SchemaVersionSelect;
