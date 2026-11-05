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
import ExpandLessOutlined from "@mui/icons-material/ExpandLessOutlined";
import ExpandMoreOutlined from "@mui/icons-material/ExpandMoreOutlined";
import ForwardToInboxOutlined from "@mui/icons-material/ForwardToInboxOutlined";
import GroupsOutlined from "@mui/icons-material/GroupsOutlined";
import TaskAltOutlined from "@mui/icons-material/TaskAltOutlined";
import { Badge, Box, Button, ButtonBase, IconButton, List, ListItemButton, Paper, Stack, Tooltip,
  Typography } from "@mui/material";

import TagChip from "@iap/tags/TagChip";

import { PersonName } from "./people";
import { SEVERITIES, type ReviewNote } from "./reviewData";
import { PANEL_HEIGHT, PanelBody } from "./sidePanel";

const bySeverity = (notes: ReviewNote[]) =>
  SEVERITIES.flatMap(severity => notes.filter(note => (note.severity ?? "medium") === severity));

// PRETOTYPE. The reviewer's review, beside whatever they are reading. On top, the issues they raised for the study
// team, most serious first: what the letter of review will hold. Under them, foldable, the AI pre-review for their
// group: its overall read, then what it raised that the reviewer has neither made an issue of nor dismissed. Each
// one leads to where it was raised. Requesting changes, approving and escalating stay at the bottom, in view.
// Folded, the panel keeps the count and the actions.
export default function ReviewPanel({ issues, aiNotes, aiSummary, about, open, onToggle, aiOpen, onToggleAi, onOpen,
  actions }: {
  issues: ReviewNote[];
  aiNotes: ReviewNote[];
  aiSummary?: string;
  about: (note: ReviewNote) => string;
  open: boolean;
  onToggle: () => void;
  aiOpen: boolean;
  onToggleAi: () => void;
  onOpen: (note: ReviewNote) => void;
  // What the reviewer can do with their review: request changes, approve (blocked, with the reason, while they
  // have issues), and, for the REB, escalate to the board
  actions?: { send: () => void; approve: () => void; approveBlocked?: string; board?: () => void };
}) {
  if (!open) {
    return (
      <Paper sx={{ width: 48, py: 1, display: "flex", flexDirection: "column", alignItems: "center", gap: 1,
        bgcolor: "background.tinted", boxShadow: "none" }}>
        <Tooltip title="Show the review" placement="left">
          <IconButton size="small" aria-label="Show the review" onClick={onToggle}>
            <Badge badgeContent={issues.length} showZero
              sx={{ "& .MuiBadge-badge": { bgcolor: "issue.main", color: "issue.contrastText" } }}>
              <ChevronLeftOutlined />
            </Badge>
          </IconButton>
        </Tooltip>
        {actions && (
          <>
            <Tooltip title="Request changes" placement="left">
              <IconButton size="small" aria-label="Request changes" color="primary"
                onClick={actions.send}>
                <ForwardToInboxOutlined />
              </IconButton>
            </Tooltip>
            <Tooltip title={actions.approveBlocked ?? "Approve"} placement="left">
              <span>
                <IconButton size="small" aria-label="Approve" color="primary" disabled={Boolean(actions.approveBlocked)}
                  onClick={actions.approve}>
                  <TaskAltOutlined />
                </IconButton>
              </span>
            </Tooltip>
            {actions.board && (
              <Tooltip title="Escalate to the board" placement="left">
                <IconButton size="small" aria-label="Escalate to the board" onClick={actions.board}>
                  <GroupsOutlined />
                </IconButton>
              </Tooltip>
            )}
          </>
        )}
      </Paper>
    );
  }
  return (
    // Part of the frame, tinted like the contents on the other side of the page
    <Paper sx={{ width: 300, display: "flex", flexDirection: "column", height: PANEL_HEIGHT,
      bgcolor: "background.tinted", boxShadow: "none" }}>
      <Stack direction="row" sx={{ alignItems: "center", gap: 1, pl: 1.5, pr: 0.5, pt: 0.75, pb: 0.5 }}>
        <Typography variant="panelTitle" sx={{ flex: 1 }}>Review</Typography>
        <Tooltip title="Fold away" placement="left">
          <IconButton size="small" aria-label="Fold the review away" onClick={onToggle}>
            <ChevronRightOutlined />
          </IconButton>
        </Tooltip>
      </Stack>
      <Box component="section" aria-label="Your issues"
        sx={{ flex: "1 1 0", minHeight: 0, display: "flex", flexDirection: "column" }}>
        <Box sx={{ px: 1.5, pb: 0.75 }}>
          <Typography variant="subtitle2" component="h3">Issues ({issues.length})</Typography>
          <Typography variant="caption" sx={{ display: "block", color: "text.secondary" }}>
            Your issues for the study team
          </Typography>
        </Box>
        <PanelBody>
          {issues.length === 0 && (
            <Typography variant="body2" sx={{ px: 1.5, py: 1, color: "text.secondary" }}>
              No issues raised yet. Write one about an answer, or about a passage of a document.
            </Typography>
          )}
          <NoteList notes={bySeverity(issues)} about={about} onOpen={onOpen}
            by={issue => <PersonName user={issue.author} />} />
        </PanelBody>
      </Box>
      <Box component="section" aria-label="AI pre-review"
        sx={{ flex: aiOpen ? "1 1 0" : "0 0 auto", minHeight: 0, display: "flex", flexDirection: "column",
          borderTop: 1, borderColor: "divider" }}>
        <ButtonBase onClick={onToggleAi} aria-expanded={aiOpen}
          sx={theme => ({ justifyContent: "flex-start", textAlign: "start", gap: 1, pl: 1.5, pr: 1, py: 0.75,
            "&:hover": { bgcolor: theme.alpha(theme.palette.background.paper, 0.5) } })}>
          <Box sx={{ flex: 1 }}>
            <Typography variant="subtitle2" component="h3">AI pre-review ({aiNotes.length})</Typography>
            <Typography variant="caption" sx={{ display: "block", color: "text.secondary" }}>
              What the AI raised for your review
            </Typography>
          </Box>
          {aiOpen ? <ExpandMoreOutlined fontSize="small" /> : <ExpandLessOutlined fontSize="small" />}
        </ButtonBase>
        {aiOpen && (
          <PanelBody>
            {aiSummary && (
              <Typography variant="body2" sx={{ px: 1.5, pb: 1, color: "text.secondary" }}>{aiSummary}</Typography>
            )}
            <NoteList notes={bySeverity(aiNotes)} about={about} onOpen={onOpen} by={note => note.topic} />
          </PanelBody>
        )}
      </Box>
      {actions && (
        <Stack spacing={1} sx={{ p: 1.5, borderTop: 1, borderColor: "divider" }}>
          <Button variant="outlined" size="small" startIcon={<ForwardToInboxOutlined />} onClick={actions.send}>
            Request changes
          </Button>
          <Tooltip title={actions.approveBlocked ?? ""}>
            <span>
              <Button variant="contained" size="small" fullWidth startIcon={<TaskAltOutlined />}
                disabled={Boolean(actions.approveBlocked)} onClick={actions.approve}>
                Approve
              </Button>
            </span>
          </Tooltip>
          {actions.board && (
            <Button variant="text" size="small" startIcon={<GroupsOutlined />} onClick={actions.board}>
              Escalate to the board
            </Button>
          )}
        </Stack>
      )}
    </Paper>
  );
}

// Notes as entries leading to where they were raised: how serious, whose or which risk, what about, and the start
// of what they say
function NoteList({ notes, about, onOpen, by }: {
  notes: ReviewNote[];
  about: (note: ReviewNote) => string;
  onOpen: (note: ReviewNote) => void;
  by: (note: ReviewNote) => React.ReactNode;
}) {
  return (
    <List dense disablePadding>
      {notes.map(note => (
        <ListItemButton key={note.id} onClick={() => onOpen(note)}
          sx={theme => ({ display: "block", px: 1.5, py: 0.75, borderTop: 1, borderColor: "divider",
            "&:hover": { bgcolor: theme.alpha(theme.palette.background.paper, 0.5) } })}>
          <Stack direction="row" sx={{ alignItems: "center", gap: 0.75, mb: 0.25 }}>
            {note.severity && <TagChip tags={[ note.severity ]} category="severity" />}
            <Typography variant="caption" sx={{ color: "text.secondary", minWidth: 0, overflow: "hidden",
              textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
              {by(note)}
            </Typography>
          </Stack>
          {about(note) && (
            <Typography variant="caption" sx={{ display: "block", color: "text.secondary", fontStyle: "italic" }}>
              {about(note)}
            </Typography>
          )}
          <Typography variant="body2" sx={{ display: "-webkit-box", WebkitBoxOrient: "vertical",
            WebkitLineClamp: 3, overflow: "hidden" }}>
            {note.text}
          </Typography>
        </ListItemButton>
      ))}
    </List>
  );
}
