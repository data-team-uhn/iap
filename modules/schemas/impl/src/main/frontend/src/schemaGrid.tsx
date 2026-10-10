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

import { dateValue, dayOf } from "@iap/frontend-commons/entityGrid/columns";
import { type EntityGridColumn, registerEntityType } from "@iap/frontend-commons/entityGrid/registry";
import type { SerializedNode } from "@iap/frontend-commons/serializedNode";
import LifecycleChip from "@iap/tags/LifecycleChip";

import { labelOf, listing, nameOf, SCHEMAS_ROOT, tagsOf, versionsOf } from "./schemaModel";
import { schemaPageUrl, versionPageUrl } from "./useSchemaList";

export const SCHEMA_TYPE = "sch/Schema";

// The schema a version row belongs to, as the grid's child rows carry it
export const SCHEMA_OF = "@schema";

const isVersion = (row: SerializedNode): boolean => row["jcr:primaryType"] === "sch:SchemaVersion";

const versionRows = (row: SerializedNode): SerializedNode[] =>
  versionsOf(row).map(version => ({ ...version, title: `Version ${labelOf(version)}`, [SCHEMA_OF]: row }));

// On a phone, a schema's card leads with its title and lifecycle tags, the day it last changed under them, and
// closes with its versions
const COLUMNS: EntityGridColumn[] = [
  { field: "title", headerName: "Schema", flex: 2, minWidth: 180, cardSlot: "title" },
  {
    field: "state",
    headerName: "State",
    width: 130,
    sortable: false,
    filterable: false,
    renderCell: params => <LifecycleChip tags={tagsOf(params.row)} />,
    cardSlot: "badge",
  },
  {
    field: "jcr:lastModified",
    headerName: "Last modified",
    width: 170,
    type: "dateTime",
    valueGetter: value => dateValue(value),
    cardSlot: "caption",
    cardValue: row => dayOf(row["jcr:lastModified"]),
  },
];

// Registered at import, so any grid importing this module can list schemas
registerEntityType(SCHEMA_TYPE, {
  homepage: SCHEMAS_ROOT,
  columns: COLUMNS,
  defaultSort: { field: "title", sort: "asc" },
  children: { selectors: listing(1), rows: versionRows, treeField: "title",
    countLabel: count => (count === 1 ? "1 version" : `${count} versions`) },
  rowLink: row => (isVersion(row)
    ? versionPageUrl(nameOf(row[SCHEMA_OF] as SerializedNode), nameOf(row))
    : schemaPageUrl(nameOf(row))),
});
