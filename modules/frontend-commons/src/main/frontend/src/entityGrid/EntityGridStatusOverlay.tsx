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

import { Button, Stack, Typography } from "@mui/material";

// What the overlay covering an empty row area should say: the plain empty message, the
// "nothing matched" message, or a fetch failure with a way to retry it.
declare module "@mui/x-data-grid" {
  interface NoRowsOverlayPropsOverrides {
    message?: string;
    error?: string;
    onRetry?: () => void;
  }
}

// The overlay shown over an empty row area. A failed fetch also empties the rows, so this is
// where the error belongs too — inside the grid, with a Retry button, while the toolbar and
// the rest of the controls stay usable around it (changing any request parameter also
// recovers on its own).
export default function EntityGridStatusOverlay(props: { message?: string; error?: string; onRetry?: () => void }) {
  const { message, error, onRetry } = props;
  if (error) {
    return (
      <Stack sx={{ height: "100%", alignItems: "center", justifyContent: "center", gap: 1, p: 2 }}>
        <Typography variant="body2" color="error" sx={{ textAlign: "center", overflowWrap: "anywhere" }}>
          {error}
        </Typography>
        <Button size="small" onClick={onRetry}>Retry</Button>
      </Stack>
    );
  }
  return (
    <Stack sx={{ height: "100%", alignItems: "center", justifyContent: "center", p: 2 }}>
      <Typography variant="placeholder">{message}</Typography>
    </Stack>
  );
}
