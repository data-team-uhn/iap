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

interface LoadingOverlayProps {
  // Whether the overlay is shown.
  open: boolean;
  // Shown above the spinner, such as "Saving changes".
  message?: string;
  // How far the work is, from 0 to 100, which the spinner then shows. Without it, the spinner turns.
  progress?: number;
}

// A dimmed backdrop over the page, with a centered spinner, while a page loads or an action works.
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
