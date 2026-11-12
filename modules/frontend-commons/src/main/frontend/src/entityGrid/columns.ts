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

// A timestamp as the entity grids show it: in the user's locale, without the seconds.
export function formatDateTime(value: Date): string {
  return value.toLocaleString(undefined, {
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "numeric",
    minute: "numeric",
  });
}

// A stored timestamp as the Date a dateTime column takes; null unless it is a string or a number
export function dateValue(value: unknown): Date | null {
  return typeof value === "string" || typeof value === "number" ? new Date(value) : null;
}

// The date of a stored timestamp in the user's locale, without the time; undefined if there is none
export const dayOf = (value: unknown): string | undefined => dateValue(value)?.toLocaleDateString();

// Gives every dateTime column without a formatter of its own the format of formatDateTime.
export function withCompactDates(columns: EntityGridColumn[]): EntityGridColumn[] {
  return columns.map(column => column.type === "dateTime" && !column.valueFormatter
    ? { ...column, valueFormatter: (value?: Date) => value ? formatDateTime(value) : "" }
    : column);
}

// Lays out every column that renders a component, and sets no `display` of its own, as a flex
// cell. The default lays a cell out on a text baseline, and a chip there sits higher than the
// plain cells beside it.
export function withElementCellsCentred(columns: EntityGridColumn[]): EntityGridColumn[] {
  return columns.map(column => column.renderCell && !column.display
    ? { ...column, display: "flex" as const }
    : column);
}
