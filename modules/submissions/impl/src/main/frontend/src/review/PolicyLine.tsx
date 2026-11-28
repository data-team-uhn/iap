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
// PRETOTYPE. The policy an issue or an AI finding rests on, and, for an AI finding under a risk the REB has standard
// questions for, when it raised none of them.

import { useContext } from "react";

import { Typography } from "@mui/material";

import { ReviewerTopics } from "./Composer";
import PolicyCitation from "./PolicyCitation";

import type { ReviewNote } from "./reviewData";

export default function PolicyLine({ note, dense = false }: { note: ReviewNote; dense?: boolean }) {
  const { all } = useContext(ReviewerTopics);
  const outside = note.kind === "aiIssue" && !note.standard
    && (all.get(note.topicPath ?? "")?.standards?.length ?? 0) > 0;
  if (!note.citation && !outside) {
    return null;
  }
  return (
    <Typography variant="caption" component="div" sx={{ color: "text.secondary", mt: dense ? 0.25 : 0.5 }}>
      {note.citation && <>Policy: <PolicyCitation citation={note.citation} /></>}
      {note.citation && outside && " · "}
      {outside && <i>Not one of the REB&apos;s standard questions</i>}
    </Typography>
  );
}
