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

import type { ReactNode } from "react";

import { dateValue, dayOf } from "@iap/frontend-commons/entityGrid/columns";
import { type EntityGridColumn, registerEntityType } from "@iap/frontend-commons/entityGrid/registry";
import { RowLink } from "@iap/frontend-commons/entityGrid/RowControls";
import type { SerializedNode } from "@iap/frontend-commons/serializedNode";
import LifecycleChip from "@iap/tags/LifecycleChip";

import { CATALOGUES_ROOT, labelOf, nameOf, tagsOf, versionPageUrl } from "./catalogueModel";

export const CATALOGUE_VERSION_TYPE = "datareq/CatalogueVersion";

// The name of the catalogue a version belongs to, read from the version's path
const catalogueNameOf = (version: SerializedNode): string => String(version["@path"]).split("/").at(-2) ?? "";

// A version's label displayed as a link to its page
function VersionLink({ row, children }: { row: SerializedNode; children: ReactNode }) {
  return <RowLink to={versionPageUrl(catalogueNameOf(row), nameOf(row))}>{children}</RowLink>;
}

// The columns of one catalogue's versions, by default in the order they were created. On a phone, a version's
// card leads with its label and lifecycle tags, with its description and creation day under them.
const COLUMNS: EntityGridColumn[] = [
  {
    field: "version",
    headerName: "Version",
    width: 140,
    valueGetter: (_value, row) => labelOf(row),
    renderCell: params => <VersionLink row={params.row}>{labelOf(params.row)}</VersionLink>,
    cardSlot: "title",
    cardValue: row => <VersionLink row={row}>{`Version ${labelOf(row)}`}</VersionLink>,
  },
  {
    field: "state",
    headerName: "State",
    width: 130,
    sortable: false,
    filterable: false,
    renderCell: params => <LifecycleChip tags={tagsOf(params.row)} />,
    cardSlot: "badge",
  },
  { field: "description", headerName: "Description", flex: 2, minWidth: 180, cardSlot: "caption" },
  {
    field: "jcr:created",
    headerName: "Created",
    width: 170,
    type: "dateTime",
    valueGetter: value => dateValue(value),
    cardSlot: "caption",
    cardValue: row => dayOf(row["jcr:created"]),
  },
];

// Registered at import, so any grid importing this module can list catalogue versions
registerEntityType(CATALOGUE_VERSION_TYPE, {
  homepage: CATALOGUES_ROOT,
  columns: COLUMNS,
  defaultSort: { field: "jcr:created", sort: "asc" },
  rowLink: row => versionPageUrl(catalogueNameOf(row), nameOf(row)),
});
