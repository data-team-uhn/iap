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

import { type ReactNode } from "react";

import { Box } from "@mui/material";

// A value that changed, shown as what it was and what it is, either missing for a value added or removed. How each
// value reads is the caller's to say.
function ValueChange({ before, after }: { before?: ReactNode; after?: ReactNode }) {
  return (
    <Box component="span" sx={{ display: "inline-flex", alignItems: "baseline", flexWrap: "wrap", gap: 0.75 }}>
      { before !== undefined && (
        <Box component="del" sx={{ bgcolor: "diff.removed.word", px: 0.5, borderRadius: 0.5 }}>{before}</Box>
      ) }
      { before !== undefined && after !== undefined && <span aria-hidden>→</span> }
      { after !== undefined && (
        <Box component="ins" sx={{ bgcolor: "diff.added.word", px: 0.5, borderRadius: 0.5, textDecoration: "none" }}>
          {after}
        </Box>
      ) }
    </Box>
  );
}

export default ValueChange;
