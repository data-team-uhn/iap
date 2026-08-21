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
 * What went wrong, in the shortest form that identifies it: the phrase for a problem, the
 * throwable's class for a failure. The two are separate properties because they are separate node
 * types, so this is computed and therefore neither sortable nor filterable server-side — `component`
 * and `operation` beside it are, and are the columns worth narrowing a long list by.
 */
export function faultLabel(row: Record<string, unknown>): string {
  const problem = row.problem;
  if (typeof problem === "string" && problem.length > 0) {
    return problem;
  }
  return simpleName(row.type);
}

function dateValue(value: unknown): Date | null {
  return typeof value === "string" || typeof value === "number" ? new Date(value) : null;
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
    // The card cannot leave a row blank — the fault is its identity and its tap target — so an
    // error carrying neither property falls back to its fingerprint
    cardValue: row => faultLabel(row) || (row["@name"] as string | undefined),
  },
  {
    field: "component",
    headerName: "Component",
    flex: 1,
    minWidth: 140,
    cardSlot: "caption",
    // The class doing the work, shown unqualified for the same reason as the fault above
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
    width: 150,
    type: "singleSelect",
    // The choices are the error-triage tag definitions under /Tags. An equality filter on the
    // multivalued property matches an error carrying that marker among others, which is what
    // "show me what needs attention" needs
    valueOptions: tagValueOptions(TRIAGE_CATEGORY),
    // Ordering by a multivalued property has no meaningful semantics
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
    // The full timestamp is too long for the caption line; the day is enough there
    cardValue: row => dateValue(row.lastOccurrence)?.toLocaleDateString(),
  },
  {
    field: "jcr:created",
    headerName: "First seen",
    width: 160,
    type: "dateTime",
    valueGetter: value => dateValue(value),
    // "Last seen" already dates the card; a second timestamp would only crowd it
    cardSlot: "omit",
  },
];

// Registering at import time means any component importing anything from this file can render an
// EntityDataGrid for recorded errors.
registerEntityType(LOGGED_ERROR_TYPE, {
  homepage: LOGGED_ERRORS_PATH,
  columns: ERROR_COLUMNS,
  // Newest fault first: what has just started happening is what somebody triaging wants to see
  defaultSort: { field: "lastOccurrence", sort: "desc" },
  // Each error is triaged on its own console page, which is not its repository path
  rowLink: row => {
    const name = row["@name"];
    return typeof name === "string" && name.length > 0 ? errorRoute(name) : undefined;
  },
});
