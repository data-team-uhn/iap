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

import { Box, Typography } from "@mui/material";

type DemoBlockProps = {
  // The parsed `iap:Extension` node registering this block, with the display parameters below.
  extension: Record<string, unknown>;
};

// A generic block for exercising the core UI extension points (the frame bars and rails, and the
// page top/bottom regions) with visible, obviously-fake content. One component serves every demo extension: the
// block labels itself with the registering extension's name, and `iap:data` can request a number
// of filler lines, tall enough to exercise scrolling behaviour (sticky bars staying pinned, side
// rails scrolling independently of the page).
function DemoBlock({ extension }: DemoBlockProps) {
  const fillerLines = Number(extension["iap:data"] ?? 0) || 0;
  return (
    <Box sx={{ p: 1, m: 0.5, border: "1px dashed", borderColor: "divider", bgcolor: "background.muted" }}>
      <Typography variant="subtitle2">{String(extension["iap:extensionName"] ?? "Demo block")}</Typography>
      {
        Array.from({ length: fillerLines }, (_, index) => (
          <Typography key={"filler-" + index} variant="body2" color="text.secondary">
            Filler line {index + 1}
          </Typography>
        ))
      }
    </Box>
  );
}

export default DemoBlock;
