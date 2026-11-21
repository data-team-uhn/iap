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

import { nounFor } from "@iap/frontend-commons/words";

const BAR_HEIGHT = 60;

/**
 * Stands in for the selection panel on a compact layout, across the foot of the catalogue. Sticky, not
 * fixed, so it stays with the catalogue when the page scrolls past it.
 */
export default function SelectionBar({ count, onOpen }: { count: number; onOpen: () => void }) {
  const empty = count === 0;
  return (
    <Box
      sx={{
        position: "sticky",
        bottom: 0,
        zIndex: 1,
        height: BAR_HEIGHT,
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
            {` ${nounFor(count, "field")} selected`}
          </Box>
        </Typography>
      </Box>
      {/* Announced as unavailable rather than disabled, so it keeps the focus the drawer gives back to it */}
      <Button
        variant="contained"
        onClick={empty ? undefined : onOpen}
        aria-disabled={empty}
        sx={{ flex: "none", ...(empty && { opacity: 0.5, cursor: "default" }) }}
      >
        Review selection
      </Button>
    </Box>
  );
}
