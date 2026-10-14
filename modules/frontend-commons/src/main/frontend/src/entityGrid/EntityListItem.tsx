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

import { Fragment, type ReactNode } from "react";

import { Box, Stack, Typography } from "@mui/material";


import { formatDateTime } from "./columns";

import type { EntityRow } from "./pagination";
import type { EntityGridColumn } from "./registry";
import type { GridRenderCellParams } from "@mui/x-data-grid-pro";

// A generic text rendering of one cell value: dates and primitives have an obvious one,
// nested objects have none — keep object-like typeofs out of the list, or they would
// stringify as "[object Object]".
function scalarContent(value: unknown): ReactNode {
  if (value instanceof Date) {
    return formatDateTime(value);
  }
  return ["string", "number", "boolean"].includes(typeof value) ? String(value) : null;
}

// One value of the narrow-screen card, rendered the way its column would render it: through
// the column's renderCell and valueGetter when present, generically otherwise. Our render
// callbacks only read the row and value, so the partial params object is enough.
export function columnContent(column: EntityGridColumn, row: EntityRow): ReactNode {
  const raw = row[column.field];
  const value = column.valueGetter
    ? (column.valueGetter as unknown as (value: unknown, row: EntityRow) => unknown)(raw, row)
    : raw;
  if (column.renderCell) {
    const renderCell =
      column.renderCell as unknown as (params: Pick<GridRenderCellParams, "row" | "value" | "field">) => ReactNode;
    return renderCell({ row, value, field: column.field });
  }
  return scalarContent(value);
}

// The card shown for one entity in list mode, composed from the visible columns according to
// their card slots: the title column leads the card, badge columns sit beside it, caption
// columns join into one muted " • " line below, and "row" columns — the default — become
// labeled rows; omitted columns don't appear at all. Content comes from each column's own
// rendering, unless its cardValue asks for a more compact form. Without a designated (or
// visible) title column, the first regular column leads the card, so a plain column list
// still makes a sensible card with no hints at all.
export default function EntityListItem({ row, columns }: { row: EntityRow; columns: EntityGridColumn[] }) {
  const content = (column: EntityGridColumn) =>
    column.cardValue ? column.cardValue(row) : columnContent(column, row);
  const shown = columns.filter(column => column.cardSlot !== "omit");
  const badges = shown.filter(column => column.cardSlot === "badge");
  const captions = shown
    .filter(column => column.cardSlot === "caption")
    .map(column => ({ field: column.field, node: content(column) }))
    .filter(part => part.node != null && part.node !== "");
  let title = shown.find(column => column.cardSlot === "title");
  let details = shown.filter(column => (column.cardSlot ?? "row") === "row");
  if (!title && details.length > 0) {
    [title, ...details] = details;
  }
  return (
    <Stack spacing={0.5} sx={{ py: 1, width: "100%" }}>
      <Stack direction="row" sx={{ gap: 1, flexWrap: "wrap", alignItems: "center", justifyContent: "space-between" }}>
        <Typography variant="subtitle2" component="div">{title && content(title)}</Typography>
        {badges.length > 0 && (
          <Stack direction="row" sx={{ gap: 0.5, flexWrap: "wrap", alignItems: "center" }}>
            {badges.map(column => <Fragment key={column.field}>{content(column)}</Fragment>)}
          </Stack>
        )}
      </Stack>
      {captions.length > 0 && (
        <Typography variant="caption" component="div">
          {captions.map((part, index) => <Fragment key={part.field}>{index > 0 && " • "}{part.node}</Fragment>)}
        </Typography>
      )}
      {details.map(column => {
        const value = content(column);
        return value == null || value === "" ? null : (
          <Stack key={column.field} direction="row" sx={{ gap: 1, flexWrap: "wrap", alignItems: "center" }}>
            <Typography variant="caption" component="div" sx={{ minWidth: 96 }}>
              {column.headerName ?? column.field}
            </Typography>
            <Box sx={{ typography: "body2" }}>{value}</Box>
          </Stack>
        );
      })}
    </Stack>
  );
}
