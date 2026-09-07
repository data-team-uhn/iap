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

import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Typography from "@mui/material/Typography";

/** How much room the bar takes, so the tree can end above it. */
export const SELECTION_BAR_HEIGHT = 60;

/**
 * What stands in for the selection panel where there is no room beside the tree.
 *
 * Sticky rather than fixed: inside a submission the catalogue is one requirement among several on a
 * scrolling page, so a bar pinned to the viewport would follow a reader out of the form it belongs
 * to and go on offering a selection they have scrolled past.
 */
export default function SelectionBar({ count, onOpen }: { count: number; onOpen: () => void }) {
  return (
    <Box
      sx={{
        position: "sticky",
        bottom: 0,
        zIndex: 1,
        height: SELECTION_BAR_HEIGHT,
        display: "flex",
        alignItems: "center",
        gap: 1.5,
        px: 2,
        bgcolor: "background.paper",
        borderTop: 1,
        borderColor: "divider",
      }}
    >
      <Box sx={{ minWidth: 0, flex: 1 }}>
        <Typography variant="h6" component="p" sx={{ lineHeight: 1.2 }}>
          {count}
          <Box component="span" sx={{ typography: "caption", fontWeight: "medium" }}>
            {` ${count === 1 ? "field" : "fields"} selected`}
          </Box>
        </Typography>
      </Box>
      <Button variant="contained" onClick={onOpen} disabled={count === 0} sx={{ flex: "none" }}>
        Review selection
      </Button>
    </Box>
  );
}
