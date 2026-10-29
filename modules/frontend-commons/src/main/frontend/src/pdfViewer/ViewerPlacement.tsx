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
import { useEffect, useRef, useState, type ReactNode } from "react";

import CloseIcon from "@mui/icons-material/Close";
import CloseFullscreenIcon from "@mui/icons-material/CloseFullscreen";
import OpenInFullIcon from "@mui/icons-material/OpenInFull";
import { Box, DialogContent, IconButton, Typography } from "@mui/material";
import { createPortal } from "react-dom";

import ResponsiveDialog from "../components/ResponsiveDialog";

import type { Breakpoint } from "@mui/material/styles";

// The space around the viewer in the dialog, kept small on a phone, where the document has the least room.
// There, the document runs to the bottom of the screen.
const DIALOG_PADDING = { px: { xs: 2, sm: 3 }, pt: { xs: 1, sm: 2.5 }, pb: { xs: 0, sm: 2.5 } };

// Shows a viewer in the element given, filling it, with a button popping it out into a dialog and back.
// Without an element, it shows the viewer in a dialog. The element needs a height of its own. While it
// is null, as a ref is before React attaches it, nothing is shown. The viewer is given the button, and
// whether it is in the dialog. Moving the viewer draws it anew, so what must survive the move belongs
// above this.
export default function ViewerPlacement({ container, width = "lg", titleId, onClose, children }: {
  container?: HTMLElement | null;
  // How wide the dialog may be. Below that width of screen, it fills the screen.
  width?: Breakpoint;
  // The id of the viewer's title, which names the dialog.
  titleId: string;
  onClose: () => void;
  children: (placed: { placeButton: ReactNode; inDialog: boolean }) => ReactNode;
}) {
  const [poppedOut, setPoppedOut] = useState(false);
  const placeButtonRef = useRef<HTMLButtonElement>(null);
  // Set when popping back in, so focus goes to the button that pops out again rather than to nowhere.
  const poppingIn = useRef(false);
  useEffect(() => {
    if (!poppedOut && poppingIn.current) {
      poppingIn.current = false;
      placeButtonRef.current?.focus();
    }
  }, [poppedOut]);
  const popIn = () => {
    poppingIn.current = true;
    setPoppedOut(false);
  };
  if (container === null) {
    return null;
  }

  const inDialog = container === undefined || poppedOut;
  const placeButton = container === undefined
    ? null
    : (
      <IconButton
        ref={placeButtonRef}
        aria-label={poppedOut ? "Pop back in" : "Pop out"}
        onClick={poppedOut ? popIn : () => setPoppedOut(true)}
      >
        {poppedOut ? <CloseFullscreenIcon /> : <OpenInFullIcon />}
      </IconButton>
    );
  const body = children({ placeButton, inDialog });

  if (inDialog) {
    return (
      <ResponsiveDialog
        open
        width={width}
        aria-labelledby={titleId}
        // Escape puts a popped-out viewer back rather than closing it.
        onClose={container !== undefined && poppedOut ? popIn : onClose}
        slotProps={{ paper: { sx: { height: "100%" } } }}
      >
        <DialogContent sx={{ display: "flex", flexDirection: "column", minHeight: 0, ...DIALOG_PADDING }}>
          {body}
        </DialogContent>
      </ResponsiveDialog>
    );
  }
  return createPortal(
    <Box sx={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0 }}>{body}</Box>,
    container,
  );
}

// The viewer's top row: its title, the button moving it between its place and the dialog, if any, and
// the one closing it.
export function ViewerTitle({ titleId, title, placeButton, onClose }: {
  titleId: string;
  title: string;
  placeButton: ReactNode;
  onClose: () => void;
}) {
  return (
    <Box sx={{ display: "flex", alignItems: "center" }}>
      <Typography id={titleId} variant="h6" component="h2" noWrap sx={{ flex: 1, minWidth: 0 }}>
        {title}
      </Typography>
      {placeButton}
      <IconButton aria-label="Close" edge="end" onClick={onClose}>
        <CloseIcon />
      </IconButton>
    </Box>
  );
}
