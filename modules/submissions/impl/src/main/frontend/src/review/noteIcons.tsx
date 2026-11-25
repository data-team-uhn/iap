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
import AutoAwesomeOutlined from "@mui/icons-material/AutoAwesomeOutlined";
import EditOutlined from "@mui/icons-material/EditOutlined";
import ErrorOutlineOutlined from "@mui/icons-material/ErrorOutlineOutlined";
import FlagOutlined from "@mui/icons-material/FlagOutlined";
import ForumOutlined from "@mui/icons-material/ForumOutlined";
import StickyNote2Outlined from "@mui/icons-material/StickyNote2Outlined";
import { type SvgIconProps } from "@mui/material";

import type { NoteKind } from "./reviewData";

// PRETOTYPE. Each kind of note's icon, as its heading and the editor's choices show it: an answer's source the page it
// is on, a contradiction a warning, the AI pre-review its sparkle, an issue a flag, raised for the study team, the reviewers' discussion speech bubbles, a note to self a sticky note,
// as reviewers keep on printouts, and a note being written a pencil
const ICONS = {
  evidence: ArticleOutlined,
  contradiction: ErrorOutlineOutlined,
  aiIssue: AutoAwesomeOutlined,
  issue: FlagOutlined,
  otherIssue: FlagOutlined,
  discussion: ForumOutlined,
  note: StickyNote2Outlined,
  draft: EditOutlined,
} as const;

export default function NoteKindIcon({ kind, ...props }: { kind: NoteKind | "draft" } & SvgIconProps) {
  const Icon = ICONS[kind];
  return <Icon {...props} />;
}
