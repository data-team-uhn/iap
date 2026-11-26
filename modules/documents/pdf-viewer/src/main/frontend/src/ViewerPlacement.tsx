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
// Where a viewer is shown: filling an element of the page, or in a dialog, with a button moving it between
// the two. Also the viewer's title row, which holds that button.
import { useEffect, useRef, useState, type ReactNode } from "react";

import CloseIcon from "@mui/icons-material/Close";
import CloseFullscreenIcon from "@mui/icons-material/CloseFullscreen";
import OpenInFullIcon from "@mui/icons-material/OpenInFull";
import { Box, DialogContent, Typography } from "@mui/material";
import { createPortal } from "react-dom";

import { ActionIcon } from "@iap/frontend-commons/components/EventAction";
import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";

import type { Breakpoint } from "@mui/material/styles";

// The space around the viewer in the dialog, kept small on a phone, where the document has the least room.
// On a phone there is none below, and the document runs to the bottom of the screen.
const DIALOG_PADDING = { px: { xs: 2, sm: 3 }, pt: { xs: 1, sm: 2.5 }, pb: { xs: 0, sm: 2.5 } };

// Shows a viewer filling the element given, with a button popping it out into a dialog and back. Without
// an element, it shows the viewer in a dialog. While the element is null, it shows nothing. `children`
// draws the viewer, given the button and whether the viewer is in the dialog. Moving the viewer draws it
// anew, so state that must survive the move belongs above this component.
export default function ViewerPlacement({ container, width = "lg", titleId, onClose, children }: {
  container?: HTMLElement | null;
  // The widest the dialog may be. On a screen narrower than that, it fills the screen.
  width?: Breakpoint;
  // The id of the viewer's title, which names the dialog.
  titleId: string;
  onClose: () => void;
  children: (placed: { placeButton: ReactNode; inDialog: boolean }) => ReactNode;
}) {
  const [poppedOut, setPoppedOut] = useState(false);
  const placeButtonRef = useRef<HTMLButtonElement>(null);
  // Set while popping back in, so the focus goes to the Pop out button once it is back, rather than being
  // lost.
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
      <ActionIcon
        ref={placeButtonRef}
        label={poppedOut ? "Pop back in" : "Pop out"}
        icon={poppedOut ? <CloseFullscreenIcon /> : <OpenInFullIcon />}
        size="medium"
        onClick={poppedOut ? popIn : () => setPoppedOut(true)}
      />
    );
  const body = children({ placeButton, inDialog });

  if (inDialog) {
    return (
      <ResponsiveDialog
        open
        width={width}
        aria-labelledby={titleId}
        // Escape puts a popped-out viewer back in its place rather than closing it.
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

// The viewer's top row: its title, then the button moving it between its place and the dialog, if it has
// one, and the Close button.
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
      <ActionIcon label="Close" icon={<CloseIcon />} size="medium" edge="end" onClick={onClose} />
    </Box>
  );
}
