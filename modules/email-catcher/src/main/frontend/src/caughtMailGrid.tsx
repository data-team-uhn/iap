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

import { CAUGHT_MAIL_PATH, CAUGHT_MESSAGE_TYPE, messageRoute } from "./caughtMailModel";

/** The subject of a message, or "(no subject)" if it has none. */
export function subjectLabel(value: unknown): string {
  return typeof value === "string" && value.length > 0 ? value : "(no subject)";
}

/** An address list as one line, the addresses separated by commas. */
export function addressLabel(value: unknown): string {
  if (Array.isArray(value)) {
    return value.filter((item): item is string => typeof item === "string").join(", ");
  }
  return typeof value === "string" ? value : "";
}

const MESSAGE_COLUMNS: EntityGridColumn[] = [
  {
    field: "subject",
    headerName: "Subject",
    flex: 2,
    minWidth: 200,
    valueGetter: value => subjectLabel(value),
    cardSlot: "title",
  },
  {
    field: "to",
    headerName: "To",
    flex: 2,
    minWidth: 180,
    // Multivalued, so not sortable. A filter matches a message that has the address among others.
    sortable: false,
    valueGetter: value => addressLabel(value),
    cardSlot: "caption",
  },
  {
    field: "from",
    headerName: "From",
    flex: 1,
    minWidth: 160,
    sortable: false,
    valueGetter: value => addressLabel(value),
    cardSlot: "omit",
  },
  {
    field: "caughtAt",
    headerName: "Caught",
    width: 180,
    type: "dateTime",
    valueGetter: value => dateValue(value),
    cardSlot: "caption",
    cardValue: row => dayOf(row.caughtAt),
  },
];

// Registered on import, so that importing this file lets an EntityDataGrid show caught messages
registerEntityType(CAUGHT_MESSAGE_TYPE, {
  homepage: CAUGHT_MAIL_PATH,
  columns: MESSAGE_COLUMNS,
  // Newest first
  defaultSort: { field: "caughtAt", sort: "desc" },
  // The message's console page, not its repository path
  rowLink: row => {
    const name = row["@name"];
    return typeof name === "string" && name.length > 0 ? messageRoute(name) : undefined;
  },
});
