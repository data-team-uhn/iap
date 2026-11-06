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
import TagChip from "@iap/tags/TagChip";
import { tagValueOptions } from "@iap/tags/tagDefinitions";

// The resource type of a submission, under which the submission grid is registered
export const SUBMISSION_TYPE = "sub/Submission";

// The text of the "Schema" cell, such as "timeOffRequest 1.0": the schema's node name, read from the
// version's path (/Schemas/<schema>/<version>), then the version's label. Empty when the
// `schemaVersion` reference is not dereferenced.
// TODO: display the schema's title, which the submission's dereferenced `schema` reference carries.
export function schemaLabel(schemaVersion: unknown): string {
  if (!schemaVersion || typeof schemaVersion !== "object") {
    return "";
  }
  const version = schemaVersion as Record<string, unknown>;
  const pathSegments = ((version["@path"] as string | undefined) ?? "").split("/");
  const schemaName = pathSegments.length > 2 ? pathSegments[pathSegments.length - 2] : "";
  return [schemaName, version.version].filter(Boolean).join(" ");
}

// The columns of every grid of submissions. The server sorts a column by the entity property it is
// named after. On the narrow-screen card, the title and the status lead, and the schema and the
// modification day make the caption line.
const SUBMISSION_COLUMNS: EntityGridColumn[] = [
  {
    field: "title",
    headerName: "Title",
    flex: 2,
    minWidth: 160,
    cardSlot: "title",
    // The card is tapped by its title, so an untitled submission shows its node name there
    cardValue: row => (row.title as string | undefined) ?? (row["@name"] as string | undefined),
  },
  {
    field: "schemaVersion",
    headerName: "Schema",
    flex: 1,
    minWidth: 120,
    // Computed from the referenced schema version, which the server can neither sort nor filter by
    sortable: false,
    filterable: false,
    valueGetter: value => schemaLabel(value),
    cardSlot: "caption",
  },
  {
    field: "tags",
    headerName: "Status",
    width: 130,
    type: "singleSelect",
    // The lifecycle tags defined under /Tags. Filtering on one matches a submission that carries it
    // among others.
    valueOptions: tagValueOptions("lifecycle"),
    // A multi-valued property has no order to sort by
    sortable: false,
    renderCell: params => <TagChip tags={params.row.tags} category="lifecycle" />,
    cardSlot: "badge",
  },
  {
    field: "jcr:created",
    headerName: "Created",
    width: 160,
    type: "dateTime",
    valueGetter: value => dateValue(value),
    // The modification day already dates the card
    cardSlot: "omit",
  },
  {
    field: "jcr:lastModified",
    headerName: "Last modified",
    width: 160,
    type: "dateTime",
    valueGetter: value => dateValue(value),
    cardSlot: "caption",
    // The day alone, which fits the caption line
    cardValue: row => dayOf(row["jcr:lastModified"]),
  },
];

// Registered on import, so that a module importing anything from this file can draw an
// EntityDataGrid of submissions.
registerEntityType(SUBMISSION_TYPE, {
  homepage: "/Submissions",
  columns: SUBMISSION_COLUMNS,
  defaultSort: { field: "jcr:lastModified", sort: "desc" },
  // Each submission is viewable on its own page, at its repository path
  rowLink: row => row["@path"] as string | undefined,
});
