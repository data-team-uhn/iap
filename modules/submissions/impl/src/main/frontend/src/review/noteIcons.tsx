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

import ArticleOutlined from "@mui/icons-material/ArticleOutlined";
import AutoAwesome from "@mui/icons-material/AutoAwesome";
import EditOutlined from "@mui/icons-material/EditOutlined";
import FactCheckOutlined from "@mui/icons-material/FactCheckOutlined";
import FlagOutlined from "@mui/icons-material/FlagOutlined";
import ForumOutlined from "@mui/icons-material/ForumOutlined";
import StickyNote2Outlined from "@mui/icons-material/StickyNote2Outlined";
import WarningAmberOutlined from "@mui/icons-material/WarningAmberOutlined";
import { Box, type SvgIconProps } from "@mui/material";

import type { NoteKind } from "./reviewData";

// PRETOTYPE. Each kind of note's icon, as its heading and the editor's choices show it: a linked answer the page it is
// on, a mismatch a warning, the AI pre-review a checklist, an issue a flag, raised for the study team, the
// reviewers' discussion speech bubbles, a note to self a sticky note, as reviewers keep on printouts, and a note being
// written a pencil
const ICONS = {
  evidence: ArticleOutlined,
  contradiction: WarningAmberOutlined,
  aiIssue: FactCheckOutlined,
  issue: FlagOutlined,
  otherIssue: FlagOutlined,
  discussion: ForumOutlined,
  note: StickyNote2Outlined,
  draft: EditOutlined,
} as const;

// What the AI made, which says so by a sparkle before its icon, the same for every such kind
const BY_AI = new Set<NoteKind | "draft">([ "evidence", "contradiction", "aiIssue" ]);

// The sizes MUI gives its icons by name, for the sparkle to keep to the icon it marks
const SIZES = { small: "1.25rem", medium: "1.5rem", large: "2.1875rem" } as const;

export default function NoteKindIcon({ kind, sx, fontSize, ...props }: { kind: NoteKind | "draft" } & SvgIconProps) {
  const Icon = ICONS[kind];
  if (!BY_AI.has(kind)) {
    return <Icon sx={sx} fontSize={fontSize} {...props} />;
  }
  // The sparkle and the icon, sized as the icon alone would be; what the caller styles, such as colour, on both
  return (
    <Box component="span"
      sx={{ display: "inline-flex", alignItems: "center", flexShrink: 0, lineHeight: 0,
        fontSize: fontSize === "inherit" ? "inherit" : SIZES[fontSize ?? "medium"], ...sx as object }}>
      <AutoAwesome sx={{ fontSize: "0.8em", mr: "0.05em" }} />
      <Icon fontSize="inherit" {...props} />
    </Box>
  );
}
