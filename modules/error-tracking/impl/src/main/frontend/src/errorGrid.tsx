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

import {
  LOGGED_ERRORS_PATH,
  LOGGED_ERROR_TYPE,
  TRIAGE_CATEGORY,
  TRIAGE_PROPERTY,
  errorRoute,
  simpleName,
} from "./errorTrackingApi";

/**
 * What went wrong, in short: the name of a problem, or the simple class name of what was thrown.
 * It comes from two properties, so the server can neither sort nor filter by it.
 */
export function faultLabel(row: Record<string, unknown>): string {
  const problem = row.problem;
  if (typeof problem === "string" && problem.length > 0) {
    return problem;
  }
  return simpleName(row.type);
}

const ERROR_COLUMNS: EntityGridColumn[] = [
  {
    field: "fault",
    headerName: "Fault",
    flex: 2,
    minWidth: 180,
    sortable: false,
    filterable: false,
    valueGetter: (_value, row) => faultLabel(row),
    cardSlot: "title",
    // The title is the card's tap target, so an error with neither property shows its fingerprint
    cardValue: row => faultLabel(row) || (row["@name"] as string | undefined),
  },
  {
    field: "component",
    headerName: "Component",
    flex: 1,
    minWidth: 140,
    cardSlot: "caption",
    // The simple class name
    valueGetter: value => simpleName(value),
  },
  {
    field: "operation",
    headerName: "Operation",
    flex: 1,
    minWidth: 120,
    cardSlot: "caption",
  },
  {
    field: TRIAGE_PROPERTY,
    headerName: "Triage",
    // Wide enough for the two tags of an acknowledged error: `acknowledged` and the resolution.
    // Grid rows have a fixed height, so a third tag would wrap out of sight
    width: 240,
    type: "singleSelect",
    // The filter's choices are the error-triage tags under /Tags. An equality filter on the
    // multi-valued property matches an error that carries that tag among others
    valueOptions: tagValueOptions(TRIAGE_CATEGORY),
    // A multi-valued property has no meaningful order
    sortable: false,
    renderCell: params => <TagChip tags={params.row[TRIAGE_PROPERTY]} category={TRIAGE_CATEGORY} />,
    cardSlot: "badge",
  },
  {
    field: "occurrences",
    headerName: "Occurrences",
    width: 120,
    type: "number",
    cardSlot: "row",
  },
  {
    field: "lastOccurrence",
    headerName: "Last seen",
    width: 160,
    type: "dateTime",
    valueGetter: value => dateValue(value),
    cardSlot: "caption",
    // The day only, since the full timestamp does not fit the caption line
    cardValue: row => dayOf(row.lastOccurrence),
  },
  {
    field: "jcr:created",
    headerName: "First seen",
    width: 160,
    type: "dateTime",
    valueGetter: value => dateValue(value),
    // "Last seen" already dates the card
    cardSlot: "omit",
  },
];

// Registered on import, so any component importing this file can render an EntityDataGrid of
// recorded errors.
registerEntityType(LOGGED_ERROR_TYPE, {
  homepage: LOGGED_ERRORS_PATH,
  columns: ERROR_COLUMNS,
  // Most recently seen first
  defaultSort: { field: "lastOccurrence", sort: "desc" },
  // Each error is triaged on its own console page, not at its repository path
  rowLink: row => {
    const name = row["@name"];
    return typeof name === "string" && name.length > 0 ? errorRoute(name) : undefined;
  },
});
