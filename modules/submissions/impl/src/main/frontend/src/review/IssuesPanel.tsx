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

import ChevronLeftOutlined from "@mui/icons-material/ChevronLeftOutlined";
import ChevronRightOutlined from "@mui/icons-material/ChevronRightOutlined";
import ForwardToInboxOutlined from "@mui/icons-material/ForwardToInboxOutlined";
import GroupsOutlined from "@mui/icons-material/GroupsOutlined";
import { Badge, Box, Button, IconButton, List, ListItemButton, Paper, Stack, Tooltip, Typography } from "@mui/material";

import TagChip from "@iap/tags/TagChip";

import { PersonName } from "./people";
import { SEVERITIES, type ReviewNote } from "./reviewData";

// PRETOTYPE. The issues the reviewer raised for the study team on the submission, most serious first, beside whatever the
// reviewer is reading: what the letter of review will hold. Each one leads to where it was raised. The letter and
// the escalation, which are made of these issues, stay at the bottom, in view. Folded, it keeps the count and the
// two actions.
export default function IssuesPanel({ issues, about, open, onToggle, onOpenIssue, actions }: {
  issues: ReviewNote[];
  about: (note: ReviewNote) => string;
  open: boolean;
  onToggle: () => void;
  onOpenIssue: (note: ReviewNote) => void;
  // The REB's letter and escalation, when the reviewer may send them
  actions?: { letter: () => void; board: () => void };
}) {
  const sorted = SEVERITIES.flatMap(severity => issues.filter(issue => (issue.severity ?? "medium") === severity));
  if (!open) {
    return (
      <Paper sx={{ width: 48, py: 1, display: "flex", flexDirection: "column", alignItems: "center", gap: 1 }}>
        <Tooltip title="Show the issues" placement="left">
          <IconButton size="small" aria-label="Show the issues" onClick={onToggle}>
            <Badge badgeContent={issues.length} showZero
              sx={{ "& .MuiBadge-badge": { bgcolor: "issue.main", color: "issue.contrastText" } }}>
              <ChevronLeftOutlined />
            </Badge>
          </IconButton>
        </Tooltip>
        {actions && (
          <>
            <Tooltip title="Letter of review" placement="left">
              <IconButton size="small" aria-label="Letter of review" color="primary" onClick={actions.letter}>
                <ForwardToInboxOutlined />
              </IconButton>
            </Tooltip>
            <Tooltip title="Escalate to the board" placement="left">
              <IconButton size="small" aria-label="Escalate to the board" onClick={actions.board}>
                <GroupsOutlined />
              </IconButton>
            </Tooltip>
          </>
        )}
      </Paper>
    );
  }
  return (
    <Paper sx={{ width: 300, display: "flex", flexDirection: "column", maxHeight: "calc(100vh - 96px)" }}>
      <Stack direction="row" sx={{ alignItems: "center", gap: 1, px: 1.5, pt: 1, pb: 0.5 }}>
        <Typography variant="panelTitle" sx={{ flex: 1 }}>
          Your issues for the study team ({issues.length})
        </Typography>
        <Tooltip title="Fold away" placement="left">
          <IconButton size="small" aria-label="Fold the issues away" onClick={onToggle}>
            <ChevronRightOutlined />
          </IconButton>
        </Tooltip>
      </Stack>
      <Box sx={{ flex: 1, overflowY: "auto", minHeight: 0 }}>
        {sorted.length === 0 && (
          <Typography variant="body2" sx={{ px: 1.5, py: 1, color: "text.secondary" }}>
            No issues raised yet. Write one about an answer, or about a passage of a document.
          </Typography>
        )}
        <List dense disablePadding>
          {sorted.map(issue => (
            <ListItemButton key={issue.id} onClick={() => onOpenIssue(issue)}
              sx={{ display: "block", px: 1.5, py: 0.75, borderTop: 1, borderColor: "divider" }}>
              <Stack direction="row" sx={{ alignItems: "center", gap: 0.75, mb: 0.25 }}>
                {issue.severity && <TagChip tags={[ issue.severity ]} category="severity" />}
                <Typography variant="caption" sx={{ color: "text.secondary", minWidth: 0, overflow: "hidden",
                  textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  <PersonName user={issue.author} />
                </Typography>
              </Stack>
              {about(issue) && (
                <Typography variant="caption" sx={{ display: "block", color: "text.secondary", fontStyle: "italic" }}>
                  {about(issue)}
                </Typography>
              )}
              <Typography variant="body2" sx={{ display: "-webkit-box", WebkitBoxOrient: "vertical",
                WebkitLineClamp: 3, overflow: "hidden" }}>
                {issue.text}
              </Typography>
            </ListItemButton>
          ))}
        </List>
      </Box>
      {actions && (
        <Stack spacing={1} sx={{ p: 1.5, borderTop: 1, borderColor: "divider" }}>
          <Button variant="contained" size="small" startIcon={<ForwardToInboxOutlined />} onClick={actions.letter}>
            Letter of review
          </Button>
          <Button variant="outlined" size="small" startIcon={<GroupsOutlined />} onClick={actions.board}>
            Escalate to the board
          </Button>
        </Stack>
      )}
    </Paper>
  );
}
