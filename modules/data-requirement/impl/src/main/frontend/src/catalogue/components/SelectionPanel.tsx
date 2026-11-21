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

import { useLayoutEffect, useRef, useState } from "react";

import ChevronLeftIcon from "@mui/icons-material/ChevronLeft";
import ChevronRightIcon from "@mui/icons-material/ChevronRight";
import CloseIcon from "@mui/icons-material/Close";
import Box from "@mui/material/Box";
import Drawer from "@mui/material/Drawer";
import Typography from "@mui/material/Typography";

import { countOf } from "@iap/frontend-commons/words";

import { useSelection } from "../useSelection";
import { rowButtonProps } from "./rowButton";
import SelectionBar from "./SelectionBar";
import SelectionContent from "./SelectionContent";

/** How wide the side panel is, which also decides when the catalogue lays itself out as compact. */
export const PANEL_WIDTH = 392;

const COLLAPSED_WIDTH = 58;

/**
 * What has been chosen, beside the tree. On a wide layout, a column that collapses to a rail showing
 * the count. On a compact one, a summary bar that opens the panel in a drawer from the right.
 */
export default function SelectionPanel({ compact }: { compact: boolean }) {
  const { selected } = useSelection();
  const count = selected.size;
  const [ drawerOpen, setDrawerOpen ] = useState(false);
  const [ panelOpen, setPanelOpen ] = useState(true);

  // Collapsing or expanding takes away the control that was pressed, so focus goes to the one that
  // replaces it. Only a reader swaps them, so every change after the first draw is one.
  const rail = useRef<HTMLDivElement>(null);
  const dismiss = useRef<HTMLButtonElement>(null);
  const drawn = useRef(false);
  useLayoutEffect(() => {
    if (drawn.current) {
      (panelOpen ? dismiss.current : rail.current)?.focus();
    }
    drawn.current = true;
  }, [ panelOpen ]);

  if (compact) {
    return (
      <>
        <SelectionBar count={count} onOpen={() => { setDrawerOpen(true); }} />
        <Drawer
          anchor="right"
          open={drawerOpen}
          onClose={() => { setDrawerOpen(false); }}
          slotProps={{
            paper: { sx: { width: "100%", maxWidth: PANEL_WIDTH }, "aria-label": "Your selection" },
          }}
        >
          <SelectionContent
            onDismiss={() => { setDrawerOpen(false); }}
            dismissLabel="Close selection"
            dismissIcon={<CloseIcon sx={{ fontSize: 18 }} />}
          />
        </Drawer>
      </>
    );
  }

  if (!panelOpen) {
    return (
      <Box
        ref={rail}
        {...rowButtonProps(() => { setPanelOpen(true); })}
        title="Expand panel"
        aria-label={`Expand selection panel, ${countOf(count, "field")} selected`}
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
        onDismiss={() => { setPanelOpen(false); }}
        dismissLabel="Collapse panel"
        dismissIcon={<ChevronRightIcon sx={{ fontSize: 18 }} />}
        dismissRef={dismiss}
      />
    </Box>
  );
}
