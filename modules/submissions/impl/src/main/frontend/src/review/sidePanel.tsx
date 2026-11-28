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

import { useEffect, useState, type ReactNode } from "react";

import ChevronLeftOutlined from "@mui/icons-material/ChevronLeftOutlined";
import ChevronRightOutlined from "@mui/icons-material/ChevronRightOutlined";
import { Box, ButtonBase, Tooltip } from "@mui/material";

import { fadeEdges, useScrollEdges } from "@iap/frontend-commons/useScrollEdges";

// PRETOTYPE. Where the review screen's side panels sit on a wide screen: just under the app bar, as tall as the
// window leaves them, staying put while the page scrolls.
// The page's own padding on a wide screen, so the panels do not shift as they start to stay put
const GAP = "24px";
export const PANEL_HEIGHT = `calc(100dvh - var(--iap-frame-top, 0px) - var(--iap-frame-bottom, 0px) - 2 * ${GAP})`;
export const panelPlacement = {
  position: "sticky",
  top: `calc(var(--iap-frame-top, 0px) + ${GAP})`,
  maxHeight: PANEL_HEIGHT,
} as const;

// What a side panel holds under its title: scrolling on its own when it is taller than the panel, faded where
// there is more. The entry marked selected is kept in view, each time `follow` changes. Without a scrollbar, the fade
// alone says there is more, for a panel whose edge must stay clear, e.g. where its selected entry meets the page.
export function PanelBody({ children, follow, noScrollbar, fadeTop = true }: {
  children: ReactNode;
  follow?: string;
  noScrollbar?: boolean;
  // Whether what scrolls out at the top fades, as it should not under something kept there
  fadeTop?: boolean;
}) {
  const [ body, setBody ] = useState<HTMLDivElement | null>(null);
  const edges = useScrollEdges(body);
  useEffect(() => {
    body?.querySelector(".Mui-selected")?.scrollIntoView({ block: "nearest" });
  }, [ body, follow ]);
  return (
    <Box ref={setBody} data-panel-body sx={{ flex: 1, minHeight: 0, overflowY: "auto", maskImage: fadeEdges({ ...edges, above: fadeTop && edges.above }),
      ...noScrollbar ? { scrollbarWidth: "none" } : {} }}>
      {children}
    </Box>
  );
}

// How wide a round fold button is, and a panel folded to its rail
export const FOLD_SIZE = 24;
export const FOLDED_WIDTH = 48;

// A round button on the edge between the page and a panel open beside it, near the top of that edge, folding the panel
// away, its chevron pointing that way; folded, the panel brings itself back from its own rail
export function FoldButton({ side, label, place, onClick }: {
  side: "left" | "right";
  label: string;
  // Where on the edge, as absolute offsets in the panel
  place: Record<string, number | string>;
  onClick: () => void;
}) {
  return (
    <Tooltip title={label} placement={side === "left" ? "right" : "left"} disableInteractive>
      <ButtonBase aria-label={label} onClick={onClick}
        sx={theme => ({ position: "absolute", top: 10, zIndex: 3, ...place, width: FOLD_SIZE, height: FOLD_SIZE,
          borderRadius: "50%", bgcolor: "background.paper", color: "text.secondary", border: 1, borderColor: "divider",
          boxShadow: 1, transition: theme.transitions.create([ "background-color", "color" ]),
          "&:hover": { bgcolor: "primary.main", color: "primary.contrastText", borderColor: "primary.main" },
          "&.Mui-focusVisible": { outline: `2px solid ${(theme.vars ?? theme).palette.primary.main}`,
            outlineOffset: 2 } })}>
        {side === "left" ? <ChevronLeftOutlined sx={{ fontSize: "1.1rem" }} />
          : <ChevronRightOutlined sx={{ fontSize: "1.1rem" }} />}
      </ButtonBase>
    </Tooltip>
  );
}
