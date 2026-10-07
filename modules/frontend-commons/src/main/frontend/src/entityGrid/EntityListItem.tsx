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

import { Box, Link, Stack, Typography } from "@mui/material";
import { Link as RouterLink } from "react-router";

import { formatDateTime } from "./columns";
import { rowId, treeName } from "./gridRows";

import type { EntityRow } from "./pagination";
import type { EntityGridColumn, EntityGridConfig } from "./registry";
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

// What can be done with an entity on its card, kept from the card, which opens the entity when pressed
export const CardActions = ({ children }: { children: ReactNode }) =>
  <Box onClick={event => event.stopPropagation()} sx={{ display: "inline-flex" }}>{children}</Box>;

// The card shown for one entity in list mode, composed from the visible columns according to
// their card slots: the title column leads the card, badge columns sit beside it, actions end its line, caption
// columns join into one muted " • " line below, and "row" columns — the default — become
// labeled rows; omitted columns don't appear at all. Content comes from each column's own
// rendering, unless its cardValue asks for a more compact form. Without a designated (or
// visible) title column, the first regular column leads the card, so a plain column list
// still makes a sensible card with no hints at all. An entity's children, for a type that has them, close the card:
// each by its tree column, linked to its page where it has one, with its badges.
export default function EntityListItem({ row, columns, config }: {
  row: EntityRow;
  columns: EntityGridColumn[];
  config?: Pick<EntityGridConfig, "children" | "rowLink">;
}) {
  const content = (column: EntityGridColumn, of: EntityRow = row) =>
    column.cardValue ? column.cardValue(of) : columnContent(column, of);
  const { children: tree, rowLink } = config ?? {};
  const children = tree ? tree.rows(row).map((child, _index, rows) =>
    ({ child, key: rowId(child, rows), name: scalarContent(treeName(child, tree)), link: rowLink?.(child) })) : [];
  const shown = columns.filter(column => column.cardSlot !== "omit");
  const badges = shown.filter(column => column.cardSlot === "badge");
  const actions = shown.filter(column => column.cardSlot === "actions");
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
        {badges.length + actions.length > 0 && (
          <Stack direction="row" sx={{ gap: 0.5, flexWrap: "wrap", alignItems: "center" }}>
            {badges.map(column => <Fragment key={column.field}>{content(column)}</Fragment>)}
            {actions.map(column => <CardActions key={column.field}>{content(column)}</CardActions>)}
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
      {children.length > 0 && (
        <Stack direction="row" sx={{ columnGap: 1.5, rowGap: 0.5, flexWrap: "wrap", alignItems: "center" }}>
          {children.map(({ child, key, name, link }) => (
            <Stack key={key} direction="row" sx={{ gap: 0.5, alignItems: "center" }}>
              { link
                ? <Link component={RouterLink} to={link} variant="body2" underline="hover"
                  onClick={event => event.stopPropagation()}>{name}</Link>
                : <Typography variant="body2" component="span">{name}</Typography> }
              {badges.map(column => <Fragment key={column.field}>{content(column, child)}</Fragment>)}
            </Stack>
          ))}
        </Stack>
      )}
    </Stack>
  );
}
