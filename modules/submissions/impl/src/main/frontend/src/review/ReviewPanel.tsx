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

import { useState, type ReactNode } from "react";

import CheckCircle from "@mui/icons-material/CheckCircle";
import ChevronLeftOutlined from "@mui/icons-material/ChevronLeftOutlined";
import ChevronRightOutlined from "@mui/icons-material/ChevronRightOutlined";
import CircleOutlined from "@mui/icons-material/CircleOutlined";
import ExpandLessOutlined from "@mui/icons-material/ExpandLessOutlined";
import ExpandMoreOutlined from "@mui/icons-material/ExpandMoreOutlined";
import ForwardToInboxOutlined from "@mui/icons-material/ForwardToInboxOutlined";
import GroupsOutlined from "@mui/icons-material/GroupsOutlined";
import TaskAltOutlined from "@mui/icons-material/TaskAltOutlined";
import {
  Badge, Box, Button, ButtonBase, IconButton, LinearProgress, Link, Paper, Stack, Tooltip, Typography,
} from "@mui/material";

import { cutShort, passageCard } from "@iap/frontend-commons/pdfViewer/passageStyles";
import TagChip from "@iap/tags/TagChip";

import NoteCard from "./NoteCard";
import { SEVERITIES, type ReviewNote, type Topic } from "./reviewData";
import { PANEL_HEIGHT, PanelBody } from "./sidePanel";

// One of the risks the reviewer's group weighs, as the review panel lists it
export interface RiskReview {
  topic: Topic;
  // The AI pre-review's read of it, and what it raised under it that the reviewer has neither made an issue of nor
  // dismissed
  assessment?: string;
  aiOpen: ReviewNote[];
  // The reviewer's own issues filed under it
  issues: ReviewNote[];
  considered: boolean;
}

// What other reviewers raised, by group: how many issues, and how many of those on what this reviewer also reviews
export interface OthersRaised {
  group: string;
  count: number;
  shared: number;
}

const bySeverity = (notes: ReviewNote[]) =>
  SEVERITIES.flatMap(severity => notes.filter(note => (note.severity ?? "medium") === severity));

const plural = (count: number, one: string, many = `${one}s`) => `${count} ${count === 1 ? one : many}`;

// PRETOTYPE. The reviewer's review, beside whatever they are reading, as the risks their group weighs: for each, what
// the AI pre-review made of it and what it raised that is still open, the reviewer's own issues filed under it, and
// whether they have considered it, counted at the top. The AI's concerns can be made issues of or dismissed right
// there, or stepped through one after the other. Under the risks, the reviewer's issues filed under none, and a line
// on what other groups raised. At the bottom, the decision, and since when it waits on the reviewer. Folded, the
// panel keeps the issue count and the actions.
export default function ReviewPanel({ risks, unfiled, issueCount, aiSummary, others, about, open, onToggle, onOpen,
  onConsider, onMakeIssue, onDismiss, onShowOthers, waitingSince, actions }: {
  risks: RiskReview[];
  unfiled: ReviewNote[];
  issueCount: number;
  aiSummary?: string;
  others: OthersRaised[];
  about: (note: ReviewNote) => string;
  open: boolean;
  onToggle: () => void;
  onOpen: (note: ReviewNote) => void;
  onConsider: (topic: string, considered: boolean) => Promise<void>;
  onMakeIssue: (note: ReviewNote, text: string, severity?: string, topic?: string) => Promise<void>;
  onDismiss: (note: ReviewNote) => Promise<void>;
  onShowOthers: () => void;
  waitingSince?: string;
  actions?: { send: () => void; approve: () => void; approveBlocked?: string; board?: () => void };
}) {
  const [ expanded, setExpanded ] = useState<string>();
  const [ summaryOpen, setSummaryOpen ] = useState(false);
  const [ lastConcern, setLastConcern ] = useState<string>();
  const concerns = risks.flatMap(risk => bySeverity(risk.aiOpen).map(note => ({ note, risk: risk.topic.path })));
  const considered = risks.filter(risk => risk.considered).length;

  if (!open) {
    return <Folded issueCount={issueCount} onToggle={onToggle} actions={actions} />;
  }

  // The AI's next open concern after the last one stepped to, round to the first
  const nextConcern = () => {
    const at = concerns.findIndex(one => one.note.id === lastConcern);
    const next = concerns[(at + 1) % concerns.length];
    setLastConcern(next.note.id);
    setExpanded(next.risk);
    onOpen(next.note);
  };

  return (
    // Part of the frame, tinted like the contents on the other side of the page
    <Paper sx={{ width: 320, display: "flex", flexDirection: "column", height: PANEL_HEIGHT,
      bgcolor: "background.tinted", boxShadow: "none" }}>
      <Stack direction="row" sx={{ alignItems: "center", pl: 1.5, pr: 0.5, pt: 0.75 }}>
        <Typography variant="panelTitle" sx={{ flex: 1 }}>Review</Typography>
        <Tooltip title="Fold away" placement="left">
          <IconButton size="small" aria-label="Fold the review away" onClick={onToggle}>
            <ChevronRightOutlined />
          </IconButton>
        </Tooltip>
      </Stack>
      <Box sx={{ px: 1.5, pb: 1 }}>
        <Typography variant="caption" sx={{ display: "block" }}>
          {considered} of {plural(risks.length, "risk")} considered
        </Typography>
        <LinearProgress
          variant="determinate" value={risks.length ? considered / risks.length * 100 : 0}
          aria-label="Risks considered" sx={{ mt: 0.5, borderRadius: 1 }}
        />
        {aiSummary && (
          <Box sx={{ mt: 1 }}>
            <Typography variant="body2" sx={{ color: "text.secondary", ...summaryOpen ? {} : cutShort(2) }}>
              <Box component="span" sx={{ color: "assistant.main", fontWeight: "fontWeightBold" }}>AI pre-review. </Box>
              {aiSummary}
            </Typography>
            <Link component="button" variant="caption" onClick={() => setSummaryOpen(!summaryOpen)}>
              {summaryOpen ? "Less" : "More"}
            </Link>
          </Box>
        )}
        {concerns.length > 0 && (
          <Button size="small" variant="outlined" fullWidth onClick={nextConcern} sx={{ mt: 1 }}>
            Next open AI concern ({concerns.length})
          </Button>
        )}
      </Box>
      <PanelBody>
        {risks.map(risk => (
          <RiskRow
            key={risk.topic.path} risk={risk} open={expanded === risk.topic.path}
            onExpand={() => setExpanded(expanded === risk.topic.path ? undefined : risk.topic.path)}
            about={about} onOpen={onOpen} onConsider={onConsider} onMakeIssue={onMakeIssue} onDismiss={onDismiss}
          />
        ))}
        {unfiled.length > 0 && (
          <Box sx={{ borderTop: 1, borderColor: "divider", px: 1.5, py: 1 }}>
            <Typography variant="caption" sx={{ display: "block", fontWeight: "fontWeightBold", mb: 0.5 }}>
              Your issues under no risk ({unfiled.length})
            </Typography>
            <IssueList issues={unfiled} about={about} onOpen={onOpen} />
          </Box>
        )}
        {others.map(one => (
          <Typography key={one.group} variant="caption"
            sx={{ display: "block", px: 1.5, py: 1, borderTop: 1, borderColor: "divider", color: "text.secondary" }}>
            {one.group} raised {plural(one.count, "issue")}
            {one.shared > 0 ? `, ${one.shared} on what you also review` : ""}.{" "}
            <Link component="button" variant="caption" onClick={onShowOthers}>Show them</Link>
          </Typography>
        ))}
      </PanelBody>
      {actions && (
        <Stack spacing={1} sx={{ p: 1.5, borderTop: 1, borderColor: "divider" }}>
          <Typography variant="caption" sx={{ color: "text.secondary" }}>
            {waitingSince ? `Waiting on your decision since ${waitingSince}` : "Not waiting on your decision now"}
          </Typography>
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

// A risk: its tick, its name and what is under it, opening onto the AI's read, its open concerns and the reviewer's
// issues
function RiskRow({ risk, open, onExpand, about, onOpen, onConsider, onMakeIssue, onDismiss }: {
  risk: RiskReview;
  open: boolean;
  onExpand: () => void;
  about: (note: ReviewNote) => string;
  onOpen: (note: ReviewNote) => void;
  onConsider: (topic: string, considered: boolean) => Promise<void>;
  onMakeIssue: (note: ReviewNote, text: string, severity?: string, topic?: string) => Promise<void>;
  onDismiss: (note: ReviewNote) => Promise<void>;
}) {
  const [ saving, setSaving ] = useState(false);
  const counts = [
    risk.aiOpen.length > 0 ? `${risk.aiOpen.length} AI` : "",
    risk.issues.length > 0 ? plural(risk.issues.length, "issue") : "",
  ].filter(Boolean).join(" · ");
  return (
    <Box sx={{ borderTop: 1, borderColor: "divider" }}>
      <Stack direction="row" sx={{ alignItems: "flex-start" }}>
        <Tooltip title={risk.considered ? "Considered. Click to unmark" : "Mark as considered"} placement="left">
          <IconButton
            size="small" disabled={saving} aria-pressed={risk.considered}
            aria-label={`${risk.topic.label} considered`} sx={{ ml: 0.75, mt: 0.5 }}
            onClick={() => {
              setSaving(true);
              void onConsider(risk.topic.path, !risk.considered).finally(() => setSaving(false));
            }}
          >
            {risk.considered ? <CheckCircle fontSize="small" color="primary" /> : <CircleOutlined fontSize="small" />}
          </IconButton>
        </Tooltip>
        <ButtonBase
          onClick={onExpand} aria-expanded={open}
          sx={theme => ({ flex: 1, minWidth: 0, justifyContent: "flex-start", textAlign: "start", py: 0.75, pl: 0.5,
            pr: 1, "&:hover": { bgcolor: theme.alpha(theme.palette.background.paper, 0.5) } })}
        >
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography variant="body2"
              sx={{ color: risk.considered ? "text.secondary" : "primary.main", fontWeight: "fontWeightMedium" }}>
              {risk.topic.label}
            </Typography>
            {counts && <Typography variant="caption" sx={{ display: "block", color: "text.secondary" }}>{counts}</Typography>}
          </Box>
          {open ? <ExpandLessOutlined fontSize="small" /> : <ExpandMoreOutlined fontSize="small" />}
        </ButtonBase>
      </Stack>
      {open && (
        <Stack spacing={1} sx={{ pl: 5, pr: 1.5, pb: 1.25 }}>
          {risk.assessment && (
            <Typography variant="caption" sx={{ color: "text.secondary" }}>
              <Box component="span" sx={{ color: "assistant.main", fontWeight: "fontWeightBold" }}>AI. </Box>
              {risk.assessment}
            </Typography>
          )}
          {bySeverity(risk.aiOpen).map(note => (
            <Card key={note.id} kind="assistant">
              <NoteCard
                note={note}
                onMakeIssue={(text, severity, topic) => onMakeIssue(note, text, severity, topic ?? risk.topic.path)}
                onDismiss={() => onDismiss(note)}
              />
              <Link component="button" variant="caption" onClick={() => onOpen(note)}>Show where</Link>
            </Card>
          ))}
          {risk.issues.length > 0 && <IssueList issues={risk.issues} about={about} onOpen={onOpen} />}
          {!risk.assessment && risk.aiOpen.length === 0 && risk.issues.length === 0 && (
            <Typography variant="caption" sx={{ color: "text.secondary" }}>
              Nothing raised here yet. {risk.topic.description?.split("\n")[0]}
            </Typography>
          )}
        </Stack>
      )}
    </Box>
  );
}

function Card({ kind, children }: { kind: "assistant" | "issue"; children: ReactNode }) {
  return <Box sx={theme => ({ ...passageCard(theme, kind), px: 1.25, py: 1 })}>{children}</Box>;
}

// The reviewer's issues, each leading to where it was raised
function IssueList({ issues, about, onOpen }: {
  issues: ReviewNote[];
  about: (note: ReviewNote) => string;
  onOpen: (note: ReviewNote) => void;
}) {
  return (
    <Stack spacing={0.75}>
      {bySeverity(issues).map(issue => (
        <ButtonBase key={issue.id} onClick={() => onOpen(issue)}
          sx={theme => ({ ...passageCard(theme, "issue"), display: "block", textAlign: "start", px: 1.25, py: 0.75 })}>
          <Stack direction="row" sx={{ alignItems: "center", gap: 0.75, mb: 0.25 }}>
            <Typography variant="caption" sx={{ color: "issue.main", fontWeight: "fontWeightBold" }}>Issue</Typography>
            {issue.severity && <TagChip tags={[ issue.severity ]} category="severity" />}
          </Stack>
          {about(issue) && (
            <Typography variant="caption" sx={{ display: "block", color: "text.secondary", fontStyle: "italic" }}>
              {about(issue)}
            </Typography>
          )}
          <Typography variant="body2" sx={cutShort(2)}>{issue.text}</Typography>
        </ButtonBase>
      ))}
    </Stack>
  );
}

// Folded, the panel keeps the count of the reviewer's issues and what they can do with them
function Folded({ issueCount, onToggle, actions }: {
  issueCount: number;
  onToggle: () => void;
  actions?: { send: () => void; approve: () => void; approveBlocked?: string; board?: () => void };
}) {
  return (
    <Paper sx={{ width: 48, py: 1, display: "flex", flexDirection: "column", alignItems: "center", gap: 1,
      bgcolor: "background.tinted", boxShadow: "none" }}>
      <Tooltip title="Show the review" placement="left">
        <IconButton size="small" aria-label="Show the review" onClick={onToggle}>
          <Badge badgeContent={issueCount} showZero
            sx={{ "& .MuiBadge-badge": { bgcolor: "issue.main", color: "issue.contrastText" } }}>
            <ChevronLeftOutlined />
          </Badge>
        </IconButton>
      </Tooltip>
      {actions && (
        <>
          <Tooltip title="Request changes" placement="left">
            <IconButton size="small" aria-label="Request changes" color="primary" onClick={actions.send}>
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
