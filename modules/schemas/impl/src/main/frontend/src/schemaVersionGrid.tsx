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

import { Link, type LinkProps, Stack, Typography } from "@mui/material";
import { Link as RouterLink } from "react-router";

import { type EntityGridColumn, registerEntityType } from "@iap/frontend-commons/entityGrid/registry";
import LifecycleChip from "@iap/tags/LifecycleChip";

import { descriptionOf, type JcrNode, labelOf, nameOf, SCHEMAS_ROOT, tagsOf } from "./schemaModel";
import { versionPageUrl } from "./useSchemaList";

export const SCHEMA_VERSION_TYPE = "sch/SchemaVersion";

const dateValue = (value: unknown) => typeof value === "string" ? new Date(value) : null;

// The versions of one schema, as its page lists them: rows it already has, sorted in the browser. The
// default order is the order they were made in; by label, numbers compare as numbers (2.0 before 10.0).
const COLUMNS: EntityGridColumn[] = [
  {
    field: "version",
    headerName: "Version",
    width: 120,
    valueGetter: (_value, row) => labelOf(row),
    sortComparator: (one: string, other: string) => one.localeCompare(other, undefined, { numeric: true }),
    renderCell: params => <VersionLink row={params.row}>{labelOf(params.row)}</VersionLink>,
  },
  {
    field: "state",
    headerName: "State",
    width: 130,
    sortable: false,
    filterable: false,
    renderCell: params => <LifecycleChip tags={tagsOf(params.row)} />,
  },
  { field: "description", headerName: "Description", flex: 2, minWidth: 180 },
  {
    field: "jcr:created",
    headerName: "Created",
    width: 170,
    type: "dateTime",
    valueGetter: value => dateValue(value),
  },
];

// The schema a version belongs to, by name, from where the version lives
const schemaNameOf = (version: JcrNode): string => String(version["@path"]).split("/").at(-2) ?? "";

// A version's label displayed as a link to its page
function VersionLink({ row, children, variant }: { row: JcrNode } & Pick<LinkProps, "children" | "variant">) {
  return (
    <Link component={RouterLink} to={versionPageUrl(schemaNameOf(row), nameOf(row))} underline="hover"
      variant={variant} onClick={event => event.stopPropagation()}>
      {children}
    </Link>
  );
}

// A version on a phone: its label and lifecycle tags, and its description
function VersionCard({ row }: { row: JcrNode }) {
  return (
    <Stack spacing={0.5} sx={{ py: 1 }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
        <VersionLink row={row} variant="body1">{`Version ${labelOf(row)}`}</VersionLink>
        <LifecycleChip tags={tagsOf(row)} />
      </Stack>
      { descriptionOf(row) && <Typography variant="description">{descriptionOf(row)}</Typography> }
    </Stack>
  );
}

// Registered at import, so any grid importing this module can list versions
registerEntityType(SCHEMA_VERSION_TYPE, {
  homepage: SCHEMAS_ROOT,
  columns: COLUMNS,
  defaultSort: { field: "jcr:created", sort: "asc" },
  rowLink: row => versionPageUrl(schemaNameOf(row), nameOf(row)),
  listItem: row => <VersionCard row={row} />,
});
