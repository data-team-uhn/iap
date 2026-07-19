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

import { Backdrop, CircularProgress, Typography } from "@mui/material";
import { alpha } from "@mui/material/styles";

type LoadingOverlayProps = {
  // Whether the overlay is shown.
  open: boolean;
  // Optional message shown under the spinner (e.g. "Saving changes").
  message?: string;
  // Optional progress value 0-100. When given, the spinner is determinate and shows this value;
  // otherwise it spins indeterminately.
  progress?: number;
};

// A loading overlay: a dimmed backdrop with a centered spinner, shown while a page or a
// long-running action is working and the UI should not be interacted with.
function LoadingOverlay({ open, message, progress }: LoadingOverlayProps) {
  const isDeterminate = typeof progress === "number";

  return (
    <Backdrop
      open={open}
      sx={theme => ({
        flexDirection: "column",
        rowGap: 2,
        color: theme.palette.text.primary,
        backgroundColor: alpha(theme.palette.background.paper, 0.7),
        zIndex: theme.zIndex.drawer + 1,
      })}
    >
      { message && <Typography variant="h6">{message}</Typography> }
      <CircularProgress variant={isDeterminate ? "determinate" : "indeterminate"} value={progress} />
    </Backdrop>
  );
}

export default LoadingOverlay;
