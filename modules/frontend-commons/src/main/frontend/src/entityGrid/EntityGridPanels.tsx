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

// The panels are rendered through the base popper slot, which forwards any extra props — sx
// included — to the underlying MUI Popper; only the slot's declared prop type is narrower.
declare module "@mui/x-data-grid" {
  interface BasePopperPropsOverrides {
    sx?: SxProps<Theme>;
  }
}

// On narrow screens the filters/columns panels cannot float: they are nearly viewport-sized,
// so the popper's collision handling shoves them wherever they fit, with no visual tie to
// their trigger. This turns them into a bottom sheet instead — the !importants beat the
// popper's own inline positioning. In list mode the panels are the only poppers around (no
// column headers means no column menu), so it is safe to restyle the shared slot.
export const BOTTOM_SHEET_SX = {
  position: "fixed !important",
  inset: "auto 0 0 0 !important",
  transform: "none !important",
  width: "100%",
  zIndex: "modal",
  // A scrim dimming the page behind the sheet, so the sheet clearly reads as a layer above
  // it. Clicks pass through to the (dimmed) page, where the panel's own click-away listener
  // picks them up — tapping outside the sheet closes it, as bottom sheets are expected to.
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
    // The stock paper is a flex container that never says which way (its one desktop child
    // doesn't care); with the sheet's header added, stacking must be explicit
    flexDirection: "column",
    borderRadius: "12px 12px 0 0",
    // The theme's outlined-paper default is a hairline that blends into the page; a sheet
    // floating over the content earns a real shadow instead
    border: 0,
    boxShadow: 8,
  },
} as const;

// What the filter conditions' stock delete icon button shows in bottom-sheet mode: a plain
// "Remove" label. On a touch card, a lone X floating above the inputs reads as anything but
// "remove this condition"; a labeled button under them says exactly what it does.
export function RemoveConditionLabel() {
  // The size is inherited: the surrounding button is typeset like a small text Button
  return <Typography variant="button" sx={{ fontSize: "inherit" }}>Remove</Typography>;
}

// The panels' frame in bottom-sheet mode: the stock GridPanel, with a header naming the open
// panel and giving the sheet its own close button — without one, the top condition's delete X
// is too easily read as "close the panel".
export function EntityGridSheetPanel(props: GridPanelProps) {
  const { children, ...frame } = props;
  const apiRef = useGridApiContext();
  const { openedPanelValue } = useGridSelector(apiRef, gridPreferencePanelStateSelector);
  const title = openedPanelValue === GridPreferencePanelsValue.columns ? "Columns" : "Filters";
  return (
    <GridPanel {...frame}>
      {/* Sticky, so the title and close button stay put while the sheet's content scrolls */}
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

// The filter panel's settings. The servlet only combines conditions with AND, so OR is not offered.
// On narrow screens each condition becomes a vertically stacked card: the usual side-by-side selects
// add up to more width than a phone has.
export function filterPanelProps(compact: boolean): Partial<GridFilterPanelProps> {
  return {
    logicOperators: [GridLogicOperator.And],
    ...compact && {
      // The full widths go through the form's own per-part props — the parts carry
      // fixed widths of their own that outside CSS would not reliably beat
      filterFormProps: {
        columnInputProps: { sx: { width: "100%" } },
        operatorInputProps: { sx: { width: "100%" } },
        valueInputProps: { sx: { width: "100%" } },
        // The stock delete control leads the form in the DOM; on the card it moves
        // below the inputs (flex order), bottom-right, and the round icon button is
        // reshaped and typeset to match the footer's small text buttons (Remove
        // all): same radius, type size and ink
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
      // "Add filter" is the panel's primary action; "Remove all" is destructive and
      // secondary, so it steps back to a quiet text style — and the footer keeps its
      // buttons apart even when a narrow panel wraps them
      "& .MuiDataGrid-panelFooter": { gap: 1, flexWrap: "wrap" },
      "& .MuiDataGrid-panelFooter .MuiButton-root + .MuiButton-root": {
        color: "text.secondary",
        border: "none",
      },
      ...compact && {
        // A soft tinted box groups each condition (and visually owns its Remove
        // button) without stacking yet another stroke over the inputs' own outlines
        "& .MuiDataGrid-filterForm": {
          flexDirection: "column",
          gap: 1,
          p: 1,
          bgcolor: "background.muted",
          borderRadius: 1,
        },
        "& .MuiDataGrid-filterForm + .MuiDataGrid-filterForm": { mt: 1 },
        // The first condition carries an invisible logic-operator placeholder (it
        // keeps the columns aligned in the row layout); in the stacked card layout
        // it is just a ghost row between the delete X and the column select
        "& .MuiDataGrid-filterForm:first-of-type .MuiDataGrid-filterFormLogicOperatorInput": {
          display: "none",
        },
        // The value wrapper is already full width, but a plain text input inside it
        // keeps its intrinsic width unless told otherwise (selects and date pickers
        // are the wrapper itself, so they already fill)
        "& .MuiDataGrid-filterFormValueInput .MuiFormControl-root": { width: "100%" },
        // The footer pins to the sheet's bottom edge, mirroring the sticky header,
        // so Add filter / Remove all stay reachable while the conditions scroll
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
