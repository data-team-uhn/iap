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

import { CAUGHT_MAIL_PATH, CAUGHT_MESSAGE_TYPE, messageRoute } from "./caughtMailApi";

/**
 * What a message says it is. A subject is not mandatory — nothing stops the platform sending a
 * message without one — and a blank cell in the column a reader identifies rows by would read as a
 * broken grid rather than as a message that carries no subject.
 */
export function subjectLabel(value: unknown): string {
  return typeof value === "string" && value.length > 0 ? value : "(no subject)";
}

/**
 * An address list as one line. Every address property is multivalued, and a message to three people
 * is one row however many addresses it names; the message's own page lists them properly.
 */
export function addressLabel(value: unknown): string {
  if (Array.isArray(value)) {
    return value.filter((item): item is string => typeof item === "string").join(", ");
  }
  return typeof value === "string" ? value : "";
}

function dateValue(value: unknown): Date | null {
  return typeof value === "string" || typeof value === "number" ? new Date(value) : null;
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
    // Multivalued, so ordering by it has no meaningful semantics. Filtering does: the servlet's
    // comparison matches an entity carrying that value among others, which is exactly what somebody
    // asking "what did we send this person" means.
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
    // Nearly always the one configured sender, so it earns a column but not a line on the card
    cardSlot: "omit",
  },
  {
    field: "caughtAt",
    headerName: "Caught",
    width: 180,
    type: "dateTime",
    valueGetter: value => dateValue(value),
    cardSlot: "caption",
    // The full timestamp is too long for the caption line; the day is enough there
    cardValue: row => dateValue(row.caughtAt)?.toLocaleDateString(),
  },
];

// Registering at import time means any component importing anything from this file can render an
// EntityDataGrid of caught messages.
registerEntityType(CAUGHT_MESSAGE_TYPE, {
  homepage: CAUGHT_MAIL_PATH,
  columns: MESSAGE_COLUMNS,
  // Newest first: what was just sent is what a developer or a test author came here to look at.
  // Sorted by when it was caught rather than by its Date header, which has second precision — two
  // messages sent in the same second would otherwise be in no particular order.
  defaultSort: { field: "caughtAt", sort: "desc" },
  // Each message is read on its own console page, which is not its repository path: the node names
  // are UUIDs, and the page is a rendering of the message rather than a dump of the node
  rowLink: row => {
    const name = row["@name"];
    return typeof name === "string" && name.length > 0 ? messageRoute(name) : undefined;
  },
});
