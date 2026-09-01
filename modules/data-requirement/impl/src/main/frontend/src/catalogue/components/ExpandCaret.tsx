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

import ChevronRightIcon from "@mui/icons-material/ChevronRight";
import Box from "@mui/material/Box";

/**
 * Which way a node is facing.
 *
 * Hidden from a screen reader: the row it sits in already says whether it is expanded, and a caret
 * announcing itself as well would say it twice.
 */
export default function ExpandCaret({ open, size = 20 }: { open: boolean; size?: number }) {
  return (
    <Box
      aria-hidden
      sx={{
        flex: "none",
        display: "flex",
        color: "text.secondary",
        transition: "transform .12s",
        transform: open ? "rotate(90deg)" : "none",
      }}
    >
      <ChevronRightIcon sx={{ fontSize: size }} />
    </Box>
  );
}
