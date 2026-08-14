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

import { ListSubheader, MenuItem, TextField } from "@mui/material";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import type { JcrNode } from "./categoryModel";

// One selectable schema version.
interface VersionOption {
  // The jcr:uuid stored in the category's schemaVersion REFERENCE
  uuid: string;
  // The version label, e.g. "1.0"
  version: string;
  active: boolean;
}

interface SchemaGroup {
  // The schema's node name, unique among its siblings, which titles are not.
  name: string;
  title: string;
  versions: VersionOption[];
}

interface SchemaVersionSelectProps {
  // The jcr:uuid of the currently selected schema version, or "" for none.
  value: string;
  onChange: (uuid: string) => void;
}

const isType = (value: unknown, primaryType: string): value is JcrNode =>
  typeof value === "object" && value !== null && (value as JcrNode)["jcr:primaryType"] === primaryType;

// Parses the /Schemas serialization into selectable groups: one group per schema, one option per
// version. Versions without an identifier cannot be referenced and are skipped.
const parseSchemas = (homepage: JcrNode): SchemaGroup[] =>
  Object.entries(homepage)
    .filter(([, schema]) => isType(schema, "sch:Schema"))
    .map(([name, schema]) => {
      const node = schema as JcrNode;
      return {
        name,
        title: (node.title as string | undefined) ?? name,
        versions: Object.values(node)
          .filter(version => isType(version, "sch:SchemaVersion"))
          .flatMap(version => {
            const uuid = version["jcr:uuid"] as string | undefined;
            return uuid ? [{
              uuid,
              version: (version.version as string | undefined) ?? "?",
              active: version.active === true,
            }] : [];
          }),
      };
    })
    .filter(group => group.versions.length > 0);

// A picker for the schema version a category is bound to, listing every version of every schema
// defined under /Schemas, grouped by schema. The "None" choice leaves the category without a
// binding, deferring its submissions to the default handling.
function SchemaVersionSelect({ value, onChange }: SchemaVersionSelectProps) {
  const [ groups, setGroups ] = useState<SchemaGroup[]>([]);
  const [ loading, setLoading ] = useState(true);
  const [ error, setError ] = useState(false);
  const authenticatedFetch = useAuthenticatedFetch();

  useEffect(() => {
    // The -dereference selector stops each version's workflow reference from inlining the whole
    // referenced workflow into the response
    authenticatedFetch("/Schemas.deep.-dereference.json")
      .then(response => response.ok
        ? response.json() as Promise<JcrNode>
        : Promise.reject(new Error(`Failed to load schemas: ${response.status}`)))
      .then(json => setGroups(parseSchemas(json)))
      .catch((err: unknown) => {
        console.error("Could not load the available schema versions", err);
        setError(true);
      })
      .finally(() => setLoading(false));
  }, [authenticatedFetch]);

  // A Select requires its options as a flat list, so subheaders and items are collected together
  const items: ReactNode[] = [
    <MenuItem key="none" value="">
      <em>None &mdash; default handling</em>
    </MenuItem>,
  ];
  groups.forEach(group => {
    items.push(<ListSubheader key={`schema-${group.name}`}>{group.title}</ListSubheader>);
    group.versions.forEach(version => {
      items.push(
        <MenuItem key={version.uuid} value={version.uuid}>
          v{version.version}{version.active ? "" : " (inactive)"}
        </MenuItem>
      );
    });
  });
  // Keep an unknown current binding selectable instead of letting the Select reject its value,
  // e.g. when the bound version's schema was removed from under it
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
