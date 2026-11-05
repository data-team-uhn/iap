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

import { useEffect, useRef, type ReactNode } from "react";

import { Box } from "@mui/material";

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
// there is more. The entry marked selected is kept in view, each time `follow` changes.
export function PanelBody({ children, follow }: { children: ReactNode; follow?: string }) {
  const body = useRef<HTMLDivElement>(null);
  const edges = useScrollEdges(body);
  useEffect(() => {
    body.current?.querySelector(".Mui-selected")?.scrollIntoView({ block: "nearest" });
  }, [follow]);
  return (
    <Box ref={body} sx={{ flex: 1, minHeight: 0, overflowY: "auto", maskImage: fadeEdges(edges) }}>
      {children}
    </Box>
  );
}
