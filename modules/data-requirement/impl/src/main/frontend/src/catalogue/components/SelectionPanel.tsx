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

import { useState, type ReactNode } from "react";

import ChevronLeftIcon from "@mui/icons-material/ChevronLeft";
import ChevronRightIcon from "@mui/icons-material/ChevronRight";
import CloseIcon from "@mui/icons-material/Close";
import Box from "@mui/material/Box";
import Drawer from "@mui/material/Drawer";
import Typography from "@mui/material/Typography";

import { useIsCompact } from "../useIsCompact";
import { useSelection } from "../useSelection";
import { rowButtonProps } from "./rowButton";
import SelectionBar from "./SelectionBar";
import SelectionContent from "./SelectionContent";

const PANEL_WIDTH = 392;

const COLLAPSED_WIDTH = 58;

/**
 * Where the selection lives, which depends on how much room there is.
 *
 * Wide: a column beside the tree, collapsible to a rail carrying the count, so a reader who wants
 * the whole width for browsing keeps the number in view. Narrow: a summary bar, with the panel
 * behind it in a drawer — from the right, the same edge it occupies at every other width, so the
 * two layouts are one idea rather than two.
 */
export default function SelectionPanel({ actions }: { actions?: ReactNode }) {
  const isCompact = useIsCompact();
  const { count } = useSelection();
  const [ sheetOpen, setSheetOpen ] = useState(false);
  const [ railOpen, setRailOpen ] = useState(true);

  if (isCompact) {
    return (
      <>
        <SelectionBar count={count} onOpen={() => { setSheetOpen(true); }} />
        <Drawer
          anchor="right"
          open={sheetOpen}
          onClose={() => { setSheetOpen(false); }}
          slotProps={{
            paper: { sx: { width: "100%", maxWidth: PANEL_WIDTH }, "aria-label": "Your selection" },
          }}
        >
          <SelectionContent
            onDismiss={() => { setSheetOpen(false); }}
            dismissLabel="Close selection"
            dismissIcon={<CloseIcon sx={{ fontSize: 18 }} />}
            actions={actions}
          />
        </Drawer>
      </>
    );
  }

  if (!railOpen) {
    return (
      <Box
        {...rowButtonProps(() => { setRailOpen(true); })}
        title="Expand panel"
        aria-label="Expand selection panel"
        sx={{
          width: COLLAPSED_WIDTH,
          flex: "none",
          bgcolor: "background.paper",
          borderLeft: 1,
          borderColor: "divider",
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          gap: 1.75,
          pt: 2.5,
          cursor: "pointer",
          "&:hover": { bgcolor: "action.hover" },
        }}
      >
        <ChevronLeftIcon sx={{ fontSize: 18, color: "text.secondary" }} />
        <Typography variant="h6" component="p">{count}</Typography>
      </Box>
    );
  }

  return (
    <Box
      component="aside"
      aria-label="Your selection"
      sx={{
        width: PANEL_WIDTH,
        flex: "none",
        minHeight: 0,
        bgcolor: "background.paper",
        borderLeft: 1,
        borderColor: "divider",
      }}
    >
      <SelectionContent
        onDismiss={() => { setRailOpen(false); }}
        dismissLabel="Collapse panel"
        dismissIcon={<ChevronRightIcon sx={{ fontSize: 18 }} />}
        actions={actions}
      />
    </Box>
  );
}
