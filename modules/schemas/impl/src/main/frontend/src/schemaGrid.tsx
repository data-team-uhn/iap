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

import { Stack, Typography } from "@mui/material";

import { type EntityGridColumn, registerEntityType } from "@iap/frontend-commons/entityGrid/registry";

import LifecycleChip from "./LifecycleChip";
import { type JcrNode, labelOf, nameOf, pathOf, SCHEMAS_ROOT, tagsOf, titleOf, versionsOf } from "./schemaModel";
import { listing } from "./useNode";
import { schemaPageUrl, versionPageUrl } from "./useSchemaList";

export const SCHEMA_TYPE = "sch/Schema";

// The schema a version row belongs to, as the grid's child rows carry it
export const SCHEMA_OF = "@schema";

const isVersion = (row: JcrNode): boolean => row["jcr:primaryType"] === "sch:SchemaVersion";

const versionRows = (row: JcrNode): JcrNode[] =>
  versionsOf(row).map(version => ({ ...version, title: `Version ${labelOf(version)}`, [SCHEMA_OF]: row }));


const dateValue = (value: unknown) => typeof value === "string" ? new Date(value) : null;

const COLUMNS: EntityGridColumn[] = [
  { field: "title", headerName: "Schema", flex: 2, minWidth: 180 },
  {
    field: "state",
    headerName: "State",
    width: 130,
    sortable: false,
    filterable: false,
    renderCell: params => <LifecycleChip tags={params.row.tags} />,
  },
  {
    field: "jcr:lastModified",
    headerName: "Last modified",
    width: 170,
    type: "dateTime",
    valueGetter: value => dateValue(value),
  },
];

// A schema on a phone: its title and each version, with their lifecycle tags
function SchemaCard({ row }: { row: JcrNode }) {
  const schema = row;
  return (
    <Stack spacing={0.5} sx={{ py: 1 }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
        <Typography sx={{ fontWeight: "fontWeightBold", color: "primary.main" }}>{titleOf(schema)}</Typography>
        <LifecycleChip tags={tagsOf(schema)} />
      </Stack>
      <Stack direction="row" spacing={1.5} sx={{ flexWrap: "wrap" }}>
        { versionsOf(schema).length === 0
          ? <Typography variant="placeholder">No versions</Typography>
          : versionsOf(schema).map(version => (
            <Stack key={pathOf(version)} direction="row" spacing={0.5} sx={{ alignItems: "center" }}>
              <Typography variant="body2">{labelOf(version)}</Typography>
              <LifecycleChip tags={tagsOf(version)} />
            </Stack>
          )) }
      </Stack>
    </Stack>
  );
}

// Registered at import, so any grid importing this module can list schemas
registerEntityType(SCHEMA_TYPE, {
  homepage: SCHEMAS_ROOT,
  columns: COLUMNS,
  defaultSort: { field: "title", sort: "asc" },
  children: { selectors: listing(1), rows: versionRows, treeField: "title",
    countLabel: count => (count === 1 ? "1 version" : `${count} versions`) },
  rowLink: row => (isVersion(row)
    ? versionPageUrl(nameOf(row[SCHEMA_OF] as JcrNode), nameOf(row))
    : schemaPageUrl(nameOf(row))),
  listItem: row => <SchemaCard row={row} />,
});
