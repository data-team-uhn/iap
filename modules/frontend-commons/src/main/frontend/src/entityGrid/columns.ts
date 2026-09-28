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

import type { EntityGridColumn } from "./registry";

// How entity timestamps display throughout the grids: like toLocaleString, minus the seconds.
export function formatDateTime(value: Date): string {
  return value.toLocaleString(undefined, {
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "numeric",
    minute: "numeric",
  });
}

// Puts the compact timestamp rendering on every dateTime column that doesn't bring its own formatter.
export function withCompactDates(columns: EntityGridColumn[]): EntityGridColumn[] {
  return columns.map(column => column.type === "dateTime" && !column.valueFormatter
    ? { ...column, valueFormatter: (value?: Date) => value ? formatDateTime(value) : "" }
    : column);
}

// A cell rendering a component needs `display: "flex"` to center a child element. The
// default lays the cell out against a text baseline, so a chip sits high against the plain cells
// beside it.
export function withElementCellsCentred(columns: EntityGridColumn[]): EntityGridColumn[] {
  return columns.map(column => column.renderCell && !column.display
    ? { ...column, display: "flex" as const }
    : column);
}
