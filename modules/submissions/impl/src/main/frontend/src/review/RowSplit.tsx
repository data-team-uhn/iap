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

import { Box } from "@mui/material";

// How far an arrow key moves the line, and how far towards either edge it may go, as shares of the height
const STEP = 0.05;
const LEAST = 0.2;

// PRETOTYPE. A handle between two parts one above the other, dragged to share the height between them: `at` is the
// share the upper one takes. The container is what they share, measured as the drag goes.
export default function RowSplit({ at, onAt, container, label }: {
  at: number;
  onAt: (at: number) => void;
  container: () => HTMLElement | null;
  label: string;
}) {
  const dragging = useRef(false);
  const clamp = (value: number) => Math.min(1 - LEAST, Math.max(LEAST, value));
  const down = (event: PointerEvent<HTMLElement>) => {
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    dragging.current = true;
    document.body.style.cursor = "row-resize";
    document.body.style.userSelect = "none";
  };
  const move = (event: PointerEvent<HTMLElement>) => {
    const box = container()?.getBoundingClientRect();
    if (dragging.current && box && box.height > 0) {
      onAt(clamp((event.clientY - box.top) / box.height));
    }
  };
  const up = () => {
    dragging.current = false;
    document.body.style.cursor = "";
    document.body.style.userSelect = "";
  };
  const keys = (event: KeyboardEvent<HTMLElement>) => {
    const by = { ArrowUp: -STEP, ArrowDown: STEP }[event.key];
    if (by !== undefined) {
      event.preventDefault();
      onAt(clamp(at + by));
    }
  };
  return (
    <Box
      role="separator" aria-orientation="horizontal" aria-label={label} aria-valuenow={Math.round(at * 100)}
      tabIndex={0} onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up} onKeyDown={keys}
      // A strip to catch the pointer, drawn as a faint line that stands out under the pointer and while dragged
      sx={theme => ({
        height: 12, flexShrink: 0, cursor: "row-resize", touchAction: "none", position: "relative",
        "&::after": { content: "\"\"", position: "absolute", left: 0, right: 0, top: "50%", height: 2,
          transform: "translateY(-50%)", bgcolor: "divider", transition: theme.transitions.create("background-color") },
        "&:hover::after, &:active::after, &:focus-visible::after": { bgcolor: "primary.main" },
        "&:focus-visible": { outline: "none" },
      })}
    />
  );
}
