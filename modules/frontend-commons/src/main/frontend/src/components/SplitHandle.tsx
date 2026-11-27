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

import { useRef, useState, type KeyboardEvent, type PointerEvent } from "react";

import DragIndicator from "@mui/icons-material/DragIndicator";
import { Box, Tooltip, type Theme } from "@mui/material";
import { type SystemStyleObject } from "@mui/system";

// How far an arrow key moves the split
const STEP = 16;

// PRETOTYPE. A handle between two side-by-side parts, dragged to share the width between them. It sizes the part on
// its right, kept between `min` and whatever leaves the part on its left `reserve` of the container's width. The
// container is measured when the drag starts, so a handle placed anywhere inside it works. A grip on it, and a tip
// under the pointer, say it can be dragged; a double click puts it back where it starts, when it is told how.
export default function SplitHandle({ width, onWidth, container, min, reserve, label, sx, onReset }: {
  width: number;
  onWidth: (width: number) => void;
  container: () => HTMLElement | null;
  min: number;
  reserve: number;
  label: string;
  // Where it goes, when not simply between the two in a row
  sx?: (theme: Theme) => SystemStyleObject<Theme>;
  onReset?: () => void;
}) {
  const start = useRef<{ x: number; width: number; max: number }>(undefined);
  // While dragged, the tip stays out of the way
  const [ dragging, setDragging ] = useState(false);
  const maxWidth = () => Math.max(min, (container()?.getBoundingClientRect().width ?? 0) - reserve);
  const clamp = (value: number, max: number) => Math.round(Math.min(max, Math.max(min, value)));

  const down = (event: PointerEvent<HTMLElement>) => {
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    start.current = { x: event.clientX, width, max: maxWidth() };
    setDragging(true);
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
    setDragging(false);
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
    <Tooltip followCursor disableInteractive describeChild
      title={dragging ? "" : onReset ? "Drag to resize. Double-click to put it back" : "Drag to resize"}>
      <Box
        role="separator" aria-orientation="vertical" aria-label={label} aria-valuenow={width} tabIndex={0}
        onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up} onKeyDown={keys}
        onDoubleClick={onReset}
        // A wide strip to catch the pointer, drawn as a faint line that stands out under the pointer and while dragged
        sx={theme => ({
          width: 16, flexShrink: 0, cursor: "col-resize", touchAction: "none", position: "relative",
          "&::after": { content: "\"\"", position: "absolute", top: 0, bottom: 0, left: "50%", width: 3,
            transform: "translateX(-50%)", borderRadius: 1, bgcolor: "divider",
            transition: theme.transitions.create("background-color") },
          "&:hover::after, &:active::after": { bgcolor: "primary.main" },
          "&:focus-visible": { outline: "none" },
          "&:focus-visible::after": { bgcolor: "primary.main" },
          // The grip, centred on the line, saying it can be taken hold of
          "& .split-grip": { position: "absolute", top: "50%", left: "50%", transform: "translate(-50%, -50%)",
            zIndex: 1, display: "flex", py: 0.25, borderRadius: 1, border: 1, borderColor: "divider",
            bgcolor: "background.paper", color: "text.secondary",
            transition: theme.transitions.create([ "color", "border-color" ]) },
          "&:hover .split-grip, &:active .split-grip, &:focus-visible .split-grip": { color: "primary.main",
            borderColor: "primary.main" },
          ...sx?.(theme),
        })}
      >
        <Box className="split-grip"><DragIndicator sx={{ fontSize: "1rem" }} /></Box>
      </Box>
    </Tooltip>
  );
}
