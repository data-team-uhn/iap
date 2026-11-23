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

import { useRef, type KeyboardEvent, type PointerEvent } from "react";

import { Box, type Theme } from "@mui/material";
import { type SystemStyleObject } from "@mui/system";

// How far an arrow key moves the split
const STEP = 16;

// PRETOTYPE. A handle between two side-by-side parts, dragged to share the width between them. It sizes the part on
// its right, kept between `min` and whatever leaves the part on its left `reserve` of the container's width. The
// container is measured when the drag starts, so a handle placed anywhere inside it works.
export default function SplitHandle({ width, onWidth, container, min, reserve, label, sx }: {
  width: number;
  onWidth: (width: number) => void;
  container: () => HTMLElement | null;
  min: number;
  reserve: number;
  label: string;
  // Where it goes, when not simply between the two in a row
  sx?: (theme: Theme) => SystemStyleObject<Theme>;
}) {
  const start = useRef<{ x: number; width: number; max: number }>(undefined);
  const maxWidth = () => Math.max(min, (container()?.getBoundingClientRect().width ?? 0) - reserve);
  const clamp = (value: number, max: number) => Math.round(Math.min(max, Math.max(min, value)));

  const down = (event: PointerEvent<HTMLElement>) => {
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    start.current = { x: event.clientX, width, max: maxWidth() };
    document.body.style.cursor = "col-resize";
    document.body.style.userSelect = "none";
  };
  const move = (event: PointerEvent<HTMLElement>) => {
    if (start.current) {
      onWidth(clamp(start.current.width + start.current.x - event.clientX, start.current.max));
    }
  };
  const up = () => {
    start.current = undefined;
    document.body.style.cursor = "";
    document.body.style.userSelect = "";
  };
  const keys = (event: KeyboardEvent<HTMLElement>) => {
    const by = { ArrowLeft: STEP, ArrowRight: -STEP }[event.key];
    if (by !== undefined) {
      event.preventDefault();
      onWidth(clamp(width + by, maxWidth()));
    }
  };

  return (
    <Box
      role="separator" aria-orientation="vertical" aria-label={label} aria-valuenow={width} tabIndex={0}
      onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up} onKeyDown={keys}
      // A wide strip to catch the pointer, drawn as a faint line that stands out under the pointer and while dragged
      sx={theme => ({
        width: 16, flexShrink: 0, cursor: "col-resize", touchAction: "none", position: "relative",
        "&::after": { content: "\"\"", position: "absolute", top: 0, bottom: 0, left: "50%", width: 2,
          transform: "translateX(-50%)", borderRadius: 1, bgcolor: "divider",
          transition: theme.transitions.create("background-color") },
        "&:hover::after, &:active::after": { bgcolor: "primary.main" },
        "&:focus-visible": { outline: "none" },
        "&:focus-visible::after": { bgcolor: "primary.main" },
        ...sx?.(theme),
      })}
    />
  );
}
