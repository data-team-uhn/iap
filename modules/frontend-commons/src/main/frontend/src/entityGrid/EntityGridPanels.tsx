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

import ClearIcon from "@mui/icons-material/Clear";
import { IconButton, Stack, Typography } from "@mui/material";
import {
  type GridFilterPanelProps,
  GridLogicOperator,
  GridPanel,
  type GridPanelProps,
  GridPreferencePanelsValue,
  gridPreferencePanelStateSelector,
  useGridApiContext,
  useGridSelector,
} from "@mui/x-data-grid-pro";

import type { SxProps, Theme } from "@mui/material/styles";

// The panels render through the base popper slot, which passes `sx` on to the MUI Popper. The
// slot's declared props leave it out.
declare module "@mui/x-data-grid" {
  interface BasePopperPropsOverrides {
    sx?: SxProps<Theme>;
  }
}

// The filters and columns panels on a narrow screen, docked to its bottom as a sheet. A floating
// panel there is nearly as large as the screen, and the popper places it wherever it fits, away
// from its button. `!important` overrides the popper's inline position. In list mode there are no
// column headers, so no column menu, and the panels are the only poppers this style reaches.
export const BOTTOM_SHEET_SX = {
  position: "fixed !important",
  inset: "auto 0 0 0 !important",
  transform: "none !important",
  width: "100%",
  zIndex: "modal",
  // Dims the page behind the sheet. A click goes through to the page, where the panel's own
  // click-away listener closes the sheet.
  "&::before": {
    content: '""',
    position: "fixed",
    inset: 0,
    bgcolor: "rgba(0, 0, 0, 0.5)",
    pointerEvents: "none",
    zIndex: -1,
  },
  "& .MuiDataGrid-paper": {
    width: "100%",
    maxWidth: "100%",
    maxHeight: "60vh",
    // The stock paper is a flex container with no direction, and the header has to go above the rest
    flexDirection: "column",
    borderRadius: "12px 12px 0 0",
    // A shadow instead of the theme's outline for paper, which would not stand out over the page
    border: 0,
    boxShadow: 8,
  },
} as const;

// What a filter condition's delete button shows on a narrow screen: the word "Remove" instead of
// an X.
export function RemoveConditionLabel() {
  // The size comes from the surrounding button
  return <Typography variant="button" sx={{ fontSize: "inherit" }}>Remove</Typography>;
}

// The frame of a panel on a narrow screen: the stock GridPanel, with a header that names the open
// panel and has a close button.
export function EntityGridSheetPanel(props: GridPanelProps) {
  const { children, ...frame } = props;
  const apiRef = useGridApiContext();
  const { openedPanelValue } = useGridSelector(apiRef, gridPreferencePanelStateSelector);
  const title = openedPanelValue === GridPreferencePanelsValue.columns ? "Columns" : "Filters";
  return (
    <GridPanel {...frame}>
      {/* Sticky, so that the title and close button stay in view while the sheet scrolls */}
      <Stack
        direction="row"
        sx={{
          alignItems: "center",
          justifyContent: "space-between",
          pl: 2,
          pr: 1,
          py: 1,
          position: "sticky",
          top: 0,
          zIndex: 1,
          bgcolor: "background.paper",
        }}
      >
        <Typography variant="subtitle1">{title}</Typography>
        <IconButton size="small" aria-label="Close" onClick={() => apiRef.current.hidePreferences()}>
          <ClearIcon fontSize="small" />
        </IconButton>
      </Stack>
      {children}
    </GridPanel>
  );
}

// The filter panel's settings. The conditions are always ANDed, so OR is not offered. On a narrow
// screen each condition is a card with its inputs one above the other, since side by side they are
// wider than a phone.
export function filterPanelProps(compact: boolean): Partial<GridFilterPanelProps> {
  return {
    logicOperators: [GridLogicOperator.And],
    ...compact && {
      // The widths go through the form's props for each part, since each part sets a fixed width
      // that CSS from outside does not reliably override
      filterFormProps: {
        columnInputProps: { sx: { width: "100%" } },
        operatorInputProps: { sx: { width: "100%" } },
        valueInputProps: { sx: { width: "100%" } },
        // The delete button comes first in the form. On the card, it moves to the bottom right,
        // and looks like the footer's small text buttons, such as "Remove all".
        deleteIconProps: {
          sx: {
            order: 99,
            alignSelf: "flex-end",
            "& .MuiIconButton-root": {
              borderRadius: 1,
              px: 1,
              fontSize: (theme: Theme) => theme.typography.pxToRem(13),
              color: "text.secondary",
            },
          },
        },
      },
    },
    sx: {
      // "Remove all" is a plain text button beside "Add filter", and the two keep apart when a
      // narrow panel wraps them
      "& .MuiDataGrid-panelFooter": { gap: 1, flexWrap: "wrap" },
      "& .MuiDataGrid-panelFooter .MuiButton-root + .MuiButton-root": {
        color: "text.secondary",
        border: "none",
      },
      ...compact && {
        // Each condition, with its Remove button, sits on a tinted box with no outline of its own
        "& .MuiDataGrid-filterForm": {
          flexDirection: "column",
          gap: 1,
          p: 1,
          bgcolor: "background.muted",
          borderRadius: 1,
        },
        "& .MuiDataGrid-filterForm + .MuiDataGrid-filterForm": { mt: 1 },
        // The first condition has an invisible placeholder for the AND/OR select. It aligns the
        // conditions side by side, and would leave an empty row on the card.
        "& .MuiDataGrid-filterForm:first-of-type .MuiDataGrid-filterFormLogicOperatorInput": {
          display: "none",
        },
        // A text input keeps its own width inside the full-width value wrapper. A select or a date
        // input is the wrapper itself, and fills it already.
        "& .MuiDataGrid-filterFormValueInput .MuiFormControl-root": { width: "100%" },
        // The footer sticks to the bottom of the sheet, so that "Add filter" and "Remove all" stay
        // in view while the conditions scroll
        "& .MuiDataGrid-panelFooter": {
          position: "sticky",
          bottom: 0,
          zIndex: 1,
          bgcolor: "background.paper",
        },
      },
    },
  };
}
