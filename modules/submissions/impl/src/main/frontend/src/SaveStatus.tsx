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
import ErrorOutlinedIcon from "@mui/icons-material/ErrorOutlined";
import { Box, CircularProgress, Tooltip, Typography } from "@mui/material";

export type SaveState = "idle" | "saving" | "saved" | "failed";

// What a save is doing. A save can be refused, such as when the request was submitted in another tab.
export default function SaveStatus({ state, error }: { state: SaveState; error?: string }) {
  if (state === "saving") {
    return <CircularProgress size={16} aria-label="Saving" />;
  }
  if (state === "saved") {
    return <Typography variant="caption">Saved</Typography>;
  }
  if (state === "failed") {
    // The only report that a save was refused. The text carries it, since an SvgIcon is
    // aria-hidden. The wrapper takes focus, so that a keyboard reaches the reason in the tooltip.
    return (
      <Tooltip title={error ?? "This was not saved"}>
        <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }} tabIndex={0}>
          <ErrorOutlinedIcon color="error" fontSize="small" />
          <Typography variant="caption" color="error">Not saved</Typography>
        </Box>
      </Tooltip>
    );
  }
  return null;
}
