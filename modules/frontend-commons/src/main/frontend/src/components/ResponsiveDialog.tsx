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

import { forwardRef, type ReactNode } from "react";

import CloseIcon from '@mui/icons-material/Close';
import {
  Dialog,
  DialogTitle,
  IconButton,
  useMediaQuery,
  type Breakpoint,
  type DialogProps
} from "@mui/material";
import { useTheme } from '@mui/material/styles';

type ResponsiveDialogProps = Omit<DialogProps, "title" | "onClose" | "maxWidth"> & {
  title?: ReactNode;
  width?: Breakpoint;
  withCloseButton?: boolean;
  closeDisabled?: boolean;
  onClose?: (event: object) => void;
};

// A Dialog that goes full screen on a screen narrower than its width. A click on the backdrop never
// closes it.
//
// Optional props:
// title: the dialog's title. Without it, the dialog has no title bar and no close button.
// withCloseButton: puts a close button (x) at the end of the title bar.
// closeDisabled: refuses the close button and the Escape key while it is set. A dialog sets it while
//   an action it cannot take back runs, so that the close button does not undo its disabled Cancel.
// width: the dialog's size, and the breakpoint below which it goes full screen: xs, sm, md, lg or
//   xl. Defaults to sm.
// onClose: called when the close button or Escape closes the dialog.
//
// Sample usage:
//<ResponsiveDialog
//  title="Select a subject"
//  withCloseButton
//  open={open}
//  onClose={selectSubject}
//  >
//  <DialogContent dividers>
//    {...}
//  </DialogContent>
//  <DialogActions>
//    {...}
//  </DialogActions>
//</ResponsiveDialog>
//

const ResponsiveDialog = forwardRef<HTMLDivElement, ResponsiveDialogProps>((props, ref) => {
  const {
    title,
    width = "sm",
    children,
    withCloseButton,
    closeDisabled,
    onClose,
    ...rest
  } = props;

  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down(width));

  const closeButton = withCloseButton ?
    <IconButton
      aria-label="close"
      sx={{ position: "absolute", right: 8, top: 8 }}
      onClick={onClose}
      disabled={closeDisabled}
      size="large"
    >
      <CloseIcon />
    </IconButton>
    : null;

  return (
    <Dialog
      ref={ref}
      maxWidth={width}
      fullWidth
      fullScreen={fullScreen}
      onClose={(event, reason) => {
        if (reason !== 'backdropClick' && !closeDisabled) {
          onClose?.(event);
        }
      }}
      {...rest}
    >
      { title && <DialogTitle sx={withCloseButton ? { pr: 8 } : undefined}>{title}{closeButton}</DialogTitle>}
      { children }
    </Dialog>
  );
});
ResponsiveDialog.displayName = 'ResponsiveDialog';

export default ResponsiveDialog;
