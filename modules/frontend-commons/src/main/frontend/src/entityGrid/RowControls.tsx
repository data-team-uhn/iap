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

// Clicking a row or a card opens its entity. Clicking a control inside it must not.
const keptFromRow = (event: MouseEvent) => event.stopPropagation();

// Wraps the controls in a row or a card, such as the entity's actions, so that clicking them does not
// open the entity. This includes clicks inside a dialog they open. It adds no box to the layout.
export function RowControls({ children }: { children: ReactNode }) {
  return <Box onClick={keptFromRow} sx={{ display: "contents" }}>{children}</Box>;
}

// A link inside a row or a card to the in-app URL `to`. Clicking it opens that page, not the entity.
export function RowLink({ to, variant, children }: { to: string } & Pick<LinkProps, "variant" | "children">) {
  return (
    <Link component={RouterLink} to={to} variant={variant} underline="hover" onClick={keptFromRow}>
      {children}
    </Link>
  );
}

// Wraps a column's content in RowControls, both in the grid and on the phone cards
export function withRowControls(column: EntityGridColumn): EntityGridColumn {
  const { renderCell, cardValue } = column;
  return {
    ...column,
    ...renderCell && { renderCell: params => <RowControls>{renderCell(params)}</RowControls> },
    ...cardValue && { cardValue: row => <RowControls>{cardValue(row)}</RowControls> },
  };
}
