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

import type { MouseEvent, ReactNode } from "react";

import { Box, Link, type LinkProps } from "@mui/material";
import { Link as RouterLink } from "react-router";

import type { EntityGridColumn } from "./registry";

// A click on a row or a card opens its entity, so a control inside it keeps its own clicks.
const keptFromRow = (event: MouseEvent) => event.stopPropagation();

// Holds the controls in a row or a card, such as the entity's actions. A click on them, or inside a
// dialog they open, does not reach the row. It adds no box to the layout.
export function RowControls({ children }: { children: ReactNode }) {
  return <Box onClick={keptFromRow} sx={{ display: "contents" }}>{children}</Box>;
}

// A link in a row or a card, to the in-app URL `to`. Its click opens that page and not the row's.
export function RowLink({ to, variant, children }: { to: string } & Pick<LinkProps, "variant" | "children">) {
  return (
    <Link component={RouterLink} to={to} variant={variant} underline="hover" onClick={keptFromRow}>
      {children}
    </Link>
  );
}

// A column whose content, in the grid and on a card, is held as RowControls
export function withRowControls(column: EntityGridColumn): EntityGridColumn {
  const { renderCell, cardValue } = column;
  return {
    ...column,
    ...renderCell && { renderCell: params => <RowControls>{renderCell(params)}</RowControls> },
    ...cardValue && { cardValue: row => <RowControls>{cardValue(row)}</RowControls> },
  };
}
