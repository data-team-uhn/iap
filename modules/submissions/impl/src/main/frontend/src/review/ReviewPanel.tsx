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

import AddOutlined from "@mui/icons-material/AddOutlined";
import CheckCircle from "@mui/icons-material/CheckCircle";
import ChevronLeftOutlined from "@mui/icons-material/ChevronLeftOutlined";
import ChevronRightOutlined from "@mui/icons-material/ChevronRightOutlined";
import CircleOutlined from "@mui/icons-material/CircleOutlined";
import CloseOutlined from "@mui/icons-material/CloseOutlined";
import DeleteOutlined from "@mui/icons-material/DeleteOutlined";
import EditOutlined from "@mui/icons-material/EditOutlined";
import ErrorOutlineOutlined from "@mui/icons-material/ErrorOutlineOutlined";
import ExpandLessOutlined from "@mui/icons-material/ExpandLessOutlined";
import ExpandMoreOutlined from "@mui/icons-material/ExpandMoreOutlined";
import ForwardToInboxOutlined from "@mui/icons-material/ForwardToInboxOutlined";
import GroupsOutlined from "@mui/icons-material/GroupsOutlined";
import OpenInFullOutlined from "@mui/icons-material/OpenInFullOutlined";
import TaskAltOutlined from "@mui/icons-material/TaskAltOutlined";
import {
  Badge, Box, Button, ButtonBase, Dialog, FormControlLabel, IconButton, LinearProgress, Link, Paper, Stack, Switch,
  Tooltip, Typography, useMediaQuery, useTheme,
} from "@mui/material";

import { cutShort, passageCard } from "@iap/frontend-commons/pdfViewer/passageStyles";
import TagChip from "@iap/tags/TagChip";

import Composer from "./Composer";
import IssuesGrid from "./IssuesGrid";
import { SEVERITIES, type ReviewNote, type Topic } from "./reviewData";
import { PANEL_HEIGHT, PanelBody } from "./sidePanel";

// An answer the protocol contradicts, by the extracted answer read from it
export interface Mismatch {
  question: string;
  answer: string;
  protocol: string;
  evidence: ReviewNote;
}

// One of the risks the reviewer's group weighs, as their review goes through it
export interface RiskReview {
  topic: Topic;
  // The AI pre-review's read of it; what it raised under it, still open or settled by the reviewer; and the answers
  // drawn on that the protocol contradicts
  assessment?: string;
  aiOpen: ReviewNote[];
  aiSettled: ReviewNote[];
  mismatches: Mismatch[];
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

// What the reviewer can do from their review
export interface ReviewDoing {
  open: (note: ReviewNote) => void;
  consider: (topic: string, considered: boolean) => Promise<void>;
  makeIssue: (note: ReviewNote, text: string, severity?: string, topic?: string) => Promise<void>;
  dismiss: (note: ReviewNote) => Promise<void>;
  undismiss: (note: ReviewNote) => Promise<void>;
  addIssue: (topic: string | undefined, text: string, severity?: string) => Promise<void>;
  editIssue: (note: ReviewNote, text: string, severity?: string, topic?: string) => Promise<void>;
  removeIssue: (note: ReviewNote) => Promise<void>;
  showOthers: () => void;
  decision?: { send: () => void; approve: () => void; approveBlocked?: string; board?: () => void };
}

interface ReviewProps {
  risks: RiskReview[];
  unfiled: ReviewNote[];
  issueCount: number;
  aiSummary?: string;
  others: OthersRaised[];
  about: (note: ReviewNote) => string;
  waitingSince?: string;
  doing: ReviewDoing;
}

const bySeverity = (notes: ReviewNote[]) =>
  SEVERITIES.flatMap(severity => notes.filter(note => (note.severity ?? "medium") === severity));

const plural = (count: number, one: string, many = `${one}s`) => `${count} ${count === 1 ? one : many}`;

const anchored = (note: ReviewNote) => note.subjects.length > 0 || Boolean(note.passage);

// "2 AI · 1 mismatch · 1 issue", what is under a risk
function countsOf(risk: RiskReview): string {
  return [
    risk.aiOpen.length > 0 ? `${risk.aiOpen.length} AI` : "",
    risk.mismatches.length > 0 ? plural(risk.mismatches.length, "mismatch", "mismatches") : "",
    risk.issues.length > 0 ? plural(risk.issues.length, "issue") : "",
  ].filter(Boolean).join(" · ");
}

// PRETOTYPE. The reviewer's review, beside whatever they are reading, as the risks their group weighs: for each, what
// the AI pre-review made of it and everything it raised under it, the answers the protocol contradicts, the
// reviewer's own issues, and whether they have considered it, counted at the top. Issues can be added under a risk
// without pointing at an answer or a passage; the AI's concerns made issues of or dismissed, and dismissals taken
// back. It opens in an almost full screen dialog to read it whole. At the bottom, the decision.
export default function ReviewPanel(props: ReviewProps & { open: boolean; onToggle: () => void }) {
  const { risks, issueCount, aiSummary, doing, open, onToggle } = props;
  const [ expanded, setExpanded ] = useState<string>();
  const [ summaryOpen, setSummaryOpen ] = useState(false);
  const [ lastConcern, setLastConcern ] = useState<string>();
  const [ whole, setWhole ] = useState(false);
  const concerns = risks.flatMap(risk => bySeverity(risk.aiOpen).filter(anchored)
    .map(note => ({ note, risk: risk.topic.path })));

  if (!open) {
    return <Folded issueCount={issueCount} onToggle={onToggle} decision={doing.decision} />;
  }

  // The AI's next open concern after the last one stepped to, round to the first
  const nextConcern = () => {
    const at = concerns.findIndex(one => one.note.id === lastConcern);
    const next = concerns[(at + 1) % concerns.length];
    setLastConcern(next.note.id);
    setExpanded(next.risk);
    doing.open(next.note);
  };

  return (
    // Part of the frame, tinted like the contents on the other side of the page
    <Paper sx={{ width: 320, display: "flex", flexDirection: "column", height: PANEL_HEIGHT,
      bgcolor: "background.tinted", boxShadow: "none" }}>
      <Stack direction="row" sx={{ alignItems: "center", pl: 1.5, pr: 0.5, pt: 0.75 }}>
        <Typography variant="panelTitle" sx={{ flex: 1 }}>Review</Typography>
        <Tooltip title="Open the whole review" placement="left">
          <IconButton size="small" aria-label="Open the whole review" onClick={() => setWhole(true)}>
            <OpenInFullOutlined fontSize="small" />
          </IconButton>
        </Tooltip>
        <Tooltip title="Fold away" placement="left">
          <IconButton size="small" aria-label="Fold the review away" onClick={onToggle}>
            <ChevronRightOutlined />
          </IconButton>
        </Tooltip>
      </Stack>
      <Box sx={{ px: 1.5, pb: 1 }}>
        <Progress risks={risks} />
        {aiSummary && (
          <Box sx={{ mt: 1 }}>
            <Typography variant="body2" sx={{ color: "text.secondary", ...summaryOpen ? {} : cutShort(2) }}>
              <AiLabel>AI pre-review. </AiLabel>{aiSummary}
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
          <Box key={risk.topic.path} sx={{ borderTop: 1, borderColor: "divider" }}>
            <RiskHeading
              risk={risk} doing={doing} open={expanded === risk.topic.path}
              onClick={() => setExpanded(expanded === risk.topic.path ? undefined : risk.topic.path)}
            />
            {expanded === risk.topic.path && (
              <Box sx={{ pl: 5, pr: 1.5, pb: 1.25 }}>
                <RiskDetail risk={risk} dense about={props.about} doing={doing} onShow={doing.open} />
              </Box>
            )}
          </Box>
        ))}
        <Unfiled {...props} dense onShow={doing.open} />
        <Others others={props.others} doing={doing} />
      </PanelBody>
      <Decision waitingSince={props.waitingSince} decision={doing.decision} />
      <WholeReview {...props} open={whole} onClose={() => setWhole(false)} />
    </Paper>
  );
}

// The review whole, almost full screen: the risks down the side, the one chosen read in full beside them, with what
// its schema says to look at
function WholeReview(props: ReviewProps & { open: boolean; onClose: () => void }) {
  const { risks, aiSummary, doing, open, onClose } = props;
  const theme = useTheme();
  const phone = useMediaQuery(theme.breakpoints.down("sm"));
  const [ chosen, setChosen ] = useState<string>();
  const [ withConcerns, setWithConcerns ] = useState(false);
  const risk = risks.find(one => one.topic.path === chosen);
  // Showing where something was raised leaves the review for the page
  const show = (note: ReviewNote) => {
    onClose();
    doing.open(note);
  };
  return (
    <Dialog
      open={open} onClose={onClose} fullScreen={phone} maxWidth={false} aria-labelledby="whole-review-title"
      slotProps={{ paper: { sx: { width: { sm: "calc(100% - 32px)" }, height: { sm: "calc(100% - 32px)" },
        maxWidth: "none", m: { sm: 2 }, display: "flex", flexDirection: "column", bgcolor: "background.paper" } } }}
    >
      <Stack direction="row" sx={{ alignItems: "center", gap: 2, px: 2.5, py: 1.5, borderBottom: 1,
        borderColor: "divider" }}>
        <Typography id="whole-review-title" variant="h6" component="h2">Your review</Typography>
        <Box sx={{ flex: 1, maxWidth: 360 }}><Progress risks={risks} /></Box>
        <Box sx={{ flex: 1 }} />
        <IconButton aria-label="Close" onClick={onClose}><CloseOutlined /></IconButton>
      </Stack>
      <Stack direction={{ xs: "column", md: "row" }} sx={{ flex: 1, minHeight: 0 }}>
        <Box sx={{ width: { md: 340 }, flexShrink: 0, overflowY: "auto", bgcolor: "background.tinted",
          maxHeight: { xs: 240, md: "none" } }}>
          <ButtonBase
            onClick={() => setChosen(undefined)}
            sx={{ display: "block", width: "100%", textAlign: "start", px: 2, py: 1,
              bgcolor: chosen === undefined ? "background.paper" : undefined }}
          >
            <Typography variant="body2" sx={{ color: "primary.main", fontWeight: "fontWeightMedium" }}>Overview</Typography>
          </ButtonBase>
          <ButtonBase
            onClick={() => setChosen(ALL_ISSUES)}
            sx={{ display: "block", width: "100%", textAlign: "start", px: 2, py: 1, borderTop: 1, borderColor: "divider",
              bgcolor: chosen === ALL_ISSUES ? "background.paper" : undefined }}
          >
            <Typography variant="body2" sx={{ color: "primary.main", fontWeight: "fontWeightMedium" }}>
              All your issues ({props.issueCount})
            </Typography>
          </ButtonBase>
          {risks.map(one => (
            <Box key={one.topic.path} sx={{ borderTop: 1, borderColor: "divider",
              bgcolor: chosen === one.topic.path ? "background.paper" : undefined }}>
              <RiskHeading risk={one} doing={doing} onClick={() => setChosen(one.topic.path)} />
            </Box>
          ))}
          <Box sx={{ borderTop: 1, borderColor: "divider",
            bgcolor: chosen === UNFILED ? "background.paper" : undefined }}>
            <ButtonBase onClick={() => setChosen(UNFILED)}
              sx={{ display: "block", width: "100%", textAlign: "start", px: 2, py: 1 }}>
              <Typography variant="body2" sx={{ color: "primary.main", fontWeight: "fontWeightMedium" }}>
                Under no risk
              </Typography>
              <Typography variant="caption" sx={{ color: "text.secondary" }}>
                {plural(props.unfiled.length, "issue")}
              </Typography>
            </ButtonBase>
          </Box>
          <Others others={props.others} doing={doing} />
        </Box>
        <Box sx={{ flex: 1, minWidth: 0, overflowY: "auto", px: { xs: 2, md: 4 }, py: 3 }}>
          {risk && (
            <Stack spacing={2} sx={{ maxWidth: 820 }}>
              <Stack direction="row" sx={{ alignItems: "center", gap: 1 }}>
                <Typography variant="h6" component="h3" sx={{ flex: 1 }}>{risk.topic.label}</Typography>
                <ConsideredButton risk={risk} doing={doing} />
              </Stack>
              <RiskDetail risk={risk} about={props.about} doing={doing} onShow={show} />
            </Stack>
          )}
          {chosen === ALL_ISSUES && (
            <Stack spacing={2} sx={{ height: "100%", minHeight: 360 }}>
              <Stack direction="row" sx={{ alignItems: "center", gap: 2, flexWrap: "wrap" }}>
                <Typography variant="h6" component="h3" sx={{ flex: 1 }}>All your issues</Typography>
                <AddIssue doing={doing} prominent />
                <FormControlLabel
                  control={<Switch checked={withConcerns} onChange={event => setWithConcerns(event.target.checked)} />}
                  label={`Include open AI concerns (${risks.reduce((sum, one) => sum + one.aiOpen.length, 0)})`}
                />
              </Stack>
              <Box sx={{ flex: 1, minHeight: 0 }}>
                <IssuesGrid
                  issues={[ ...risks.flatMap(one => one.issues), ...props.unfiled ]}
                  concerns={withConcerns ? risks.flatMap(one => one.aiOpen) : []}
                  risks={risks.map(one => ({ path: one.topic.path, label: one.topic.label }))}
                  about={props.about} onShow={show}
                  onChange={(issue, text, severity, topic) => doing.editIssue(issue, text, severity, topic)}
                  onMakeIssue={concern => doing.makeIssue(concern,
                    concern.suggestion ? `${concern.text}\n\n${concern.suggestion}` : concern.text,
                    concern.severity, concern.topicPath)}
                  onDismiss={concern => doing.dismiss(concern)}
                />
              </Box>
            </Stack>
          )}
          {chosen === UNFILED && (
            <Stack spacing={2} sx={{ maxWidth: 820 }}>
              <Typography variant="h6" component="h3">Under no risk</Typography>
              <Unfiled {...props} onShow={show} />
            </Stack>
          )}
          {chosen === undefined && (
            <Stack spacing={2} sx={{ maxWidth: 820 }}>
              <Typography variant="h6" component="h3">Overview</Typography>
              {aiSummary && (
                <Typography variant="body1"><AiLabel>AI pre-review. </AiLabel>{aiSummary}</Typography>
              )}
              <Typography variant="body2" sx={{ color: "text.secondary" }}>
                {plural(risks.filter(one => !one.considered).length, "risk")} still to consider,{" "}
                {plural(risks.reduce((sum, one) => sum + one.aiOpen.length, 0), "open AI concern")},{" "}
                {plural(risks.reduce((sum, one) => sum + one.mismatches.length, 0), "answer", "answers")} the
                protocol contradicts, and {plural(props.issueCount, "issue")} of yours. Choose a risk to go through it.
              </Typography>
            </Stack>
          )}
        </Box>
      </Stack>
      <Decision waitingSince={props.waitingSince} decision={doing.decision} wide />
    </Dialog>
  );
}

const UNFILED = "unfiled";
const ALL_ISSUES = "all";

function Progress({ risks }: { risks: RiskReview[] }) {
  const considered = risks.filter(risk => risk.considered).length;
  return (
    <>
      <Typography variant="caption" sx={{ display: "block" }}>
        {considered} of {plural(risks.length, "risk")} considered
      </Typography>
      <LinearProgress
        variant="determinate" value={risks.length ? considered / risks.length * 100 : 0}
        aria-label="Risks considered" sx={{ mt: 0.5, borderRadius: 1 }}
      />
    </>
  );
}

function AiLabel({ children }: { children: ReactNode }) {
  return <Box component="span" sx={{ color: "assistant.main", fontWeight: "fontWeightBold" }}>{children}</Box>;
}

function ConsideredButton({ risk, doing }: { risk: RiskReview; doing: ReviewDoing }) {
  const [ saving, setSaving ] = useState(false);
  return (
    <Tooltip title={risk.considered ? "Considered. Click to unmark" : "Mark as considered"} placement="left">
      <IconButton
        size="small" disabled={saving} aria-pressed={risk.considered} aria-label={`${risk.topic.label} considered`}
        onClick={() => {
          setSaving(true);
          void doing.consider(risk.topic.path, !risk.considered).finally(() => setSaving(false));
        }}
      >
        {risk.considered ? <CheckCircle fontSize="small" color="primary" /> : <CircleOutlined fontSize="small" />}
      </IconButton>
    </Tooltip>
  );
}

// A risk's tick, its name and what is under it
function RiskHeading({ risk, doing, open, onClick }: {
  risk: RiskReview;
  doing: ReviewDoing;
  open?: boolean;
  onClick: () => void;
}) {
  const counts = countsOf(risk);
  return (
    <Stack direction="row" sx={{ alignItems: "flex-start" }}>
      <Box sx={{ ml: 0.75, mt: 0.5 }}><ConsideredButton risk={risk} doing={doing} /></Box>
      <ButtonBase
        onClick={onClick} aria-expanded={open}
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
        {open !== undefined && (open ? <ExpandLessOutlined fontSize="small" /> : <ExpandMoreOutlined fontSize="small" />)}
      </ButtonBase>
    </Stack>
  );
}

// Everything under a risk: in full, what the schema says to look at; the AI's read; the answers the protocol
// contradicts; what the AI raised, open then settled; the reviewer's issues; and a way to add one
function RiskDetail({ risk, dense = false, about, doing, onShow }: {
  risk: RiskReview;
  dense?: boolean;
  about: (note: ReviewNote) => string;
  doing: ReviewDoing;
  onShow: (note: ReviewNote) => void;
}) {
  const nothing = !risk.assessment && risk.aiOpen.length === 0 && risk.aiSettled.length === 0
    && risk.mismatches.length === 0 && risk.issues.length === 0;
  return (
    <Stack spacing={dense ? 1 : 2}>
      {!dense && risk.topic.description && (
        <Typography variant="body2" sx={{ whiteSpace: "pre-wrap", color: "text.secondary" }}>
          {risk.topic.description}
        </Typography>
      )}
      {risk.assessment && (
        <Typography variant={dense ? "caption" : "body2"}><AiLabel>AI. </AiLabel>{risk.assessment}</Typography>
      )}
      {risk.mismatches.length > 0 && (
        <Section title="Answers the protocol contradicts" dense={dense}>
          {risk.mismatches.map(mismatch => (
            <Stack key={mismatch.evidence.id} direction="row" sx={{ gap: 0.75, alignItems: "flex-start" }}>
              <ErrorOutlineOutlined sx={{ fontSize: "1rem", color: "warning.main", mt: 0.25 }} />
              <Typography variant={dense ? "caption" : "body2"}>
                {mismatch.question} The form says {mismatch.answer}; the protocol says {mismatch.protocol}.{" "}
                <Link component="button" variant="inherit" onClick={() => onShow(mismatch.evidence)}>Show where</Link>
              </Typography>
            </Stack>
          ))}
        </Section>
      )}
      {risk.aiOpen.length > 0 && (
        <Section title="Raised by the AI" dense={dense}>
          <Rows>
            {bySeverity(risk.aiOpen).map(note => (
              <AiRow key={note.id} note={note} topic={risk.topic.path} dense={dense} about={about} doing={doing}
                onShow={onShow} />
            ))}
          </Rows>
        </Section>
      )}
      {risk.aiSettled.length > 0 && (
        <Section title="Settled AI concerns" dense={dense}>
          <Rows>
            {bySeverity(risk.aiSettled).map(note => (
              <Settled key={note.id} note={note} doing={doing} onShow={onShow} />
            ))}
          </Rows>
        </Section>
      )}
      <Section title={`Your issues (${risk.issues.length})`} dense={dense}>
        <Issues issues={risk.issues} dense={dense} about={about} doing={doing} onShow={onShow} />
        <AddIssue topic={risk.topic.path} doing={doing} />
      </Section>
      {nothing && dense && (
        <Typography variant="caption" sx={{ color: "text.secondary" }}>
          Nothing raised here yet. {risk.topic.description?.split("\n")[0]}
        </Typography>
      )}
    </Stack>
  );
}

function Section({ title, dense, children }: { title: string; dense: boolean; children: ReactNode }) {
  return (
    <Stack spacing={dense ? 0.75 : 1}>
      <Typography variant={dense ? "caption" : "subtitle2"} component="h4" sx={{ fontWeight: "fontWeightBold" }}>
        {title}
      </Typography>
      {children}
    </Stack>
  );
}

// Items listed one under the other, plainly, apart by a line: the section says what they are
function Rows({ children }: { children: ReactNode }) {
  return <Stack divider={<Box sx={{ borderTop: 1, borderColor: "divider" }} />}>{children}</Stack>;
}

// What an item is about and how serious, in small, over what it says
function RowHead({ note, about, tools }: { note: ReviewNote; about: (note: ReviewNote) => string; tools?: ReactNode }) {
  return (
    <Stack direction="row" sx={{ alignItems: "center", gap: 0.75, minHeight: 26 }}>
      {note.severity && <TagChip tags={[ note.severity ]} category="severity" />}
      <Typography variant="caption" sx={{ flex: 1, minWidth: 0, color: "text.secondary", fontStyle: "italic",
        overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
        {about(note)}
      </Typography>
      {tools}
    </Stack>
  );
}

function Links({ children }: { children: ReactNode }) {
  return <Stack direction="row" sx={{ gap: 1.5, flexWrap: "wrap", mt: 0.25 }}>{children}</Stack>;
}

// An AI concern still open: made an issue of, filed under its risk, or dismissed, or shown where it was raised
function AiRow({ note, topic, dense, about, doing, onShow }: {
  note: ReviewNote;
  topic: string;
  dense: boolean;
  about: (note: ReviewNote) => string;
  doing: ReviewDoing;
  onShow: (note: ReviewNote) => void;
}) {
  const [ adopting, setAdopting ] = useState(false);
  return (
    <Box sx={{ py: 1 }}>
      <RowHead note={note} about={about} />
      {adopting ? (
        <Box sx={{ mt: 0.5 }}>
          <Composer
            kinds={[ "issue" ]} initialText={note.suggestion ? `${note.text}\n\n${note.suggestion}` : note.text}
            initialSeverity={note.severity} initialTopic={note.topicPath ?? topic} saveLabel="Add issue"
            onCancel={() => setAdopting(false)}
            onSave={async (_kind, text, severity, chosen) => {
              await doing.makeIssue(note, text, severity, chosen);
              setAdopting(false);
            }}
          />
        </Box>
      ) : (
        <>
          <Typography variant="body2" sx={dense ? cutShort(3) : undefined}>{note.text}</Typography>
          {!dense && note.suggestion && (
            <Typography variant="body2" sx={{ color: "text.secondary", mt: 0.5 }}>Suggestion. {note.suggestion}</Typography>
          )}
          <Links>
            <Link component="button" variant="caption" onClick={() => setAdopting(true)}>Make it an issue</Link>
            <Link component="button" variant="caption" onClick={() => void doing.dismiss(note)}>Dismiss</Link>
            {anchored(note) && <Link component="button" variant="caption" onClick={() => onShow(note)}>Show where</Link>}
          </Links>
        </>
      )}
    </Box>
  );
}

// One of the reviewer's issues: changed or removed in place, or shown where it was raised
function IssueRow({ issue, dense, about, doing, onShow }: {
  issue: ReviewNote;
  dense: boolean;
  about: (note: ReviewNote) => string;
  doing: ReviewDoing;
  onShow: (note: ReviewNote) => void;
}) {
  const [ editing, setEditing ] = useState(false);
  if (editing) {
    return (
      <Box sx={{ py: 1 }}>
        <Composer
          kinds={[ "issue" ]} initialText={issue.text} initialSeverity={issue.severity}
          initialTopic={issue.topicPath ?? ""} saveLabel="Save"
          onCancel={() => setEditing(false)}
          onSave={async (_kind, text, severity, topic) => {
            await doing.editIssue(issue, text, severity, topic);
            setEditing(false);
          }}
        />
      </Box>
    );
  }
  return (
    <Box sx={{ py: 1 }}>
      <RowHead note={issue} about={about} tools={(
        <Box sx={{ display: "flex", mr: -0.75 }}>
          <Tooltip title="Change">
            <IconButton size="small" aria-label="Change" onClick={() => setEditing(true)}>
              <EditOutlined fontSize="small" />
            </IconButton>
          </Tooltip>
          <Tooltip title="Remove">
            <IconButton size="small" aria-label="Remove" onClick={() => void doing.removeIssue(issue)}>
              <DeleteOutlined fontSize="small" />
            </IconButton>
          </Tooltip>
        </Box>
      )} />
      <Typography variant="body2" sx={{ whiteSpace: "pre-wrap", ...dense ? cutShort(3) : {} }}>{issue.text}</Typography>
      {anchored(issue) && (
        <Links>
          <Link component="button" variant="caption" onClick={() => onShow(issue)}>Show where</Link>
        </Links>
      )}
    </Box>
  );
}

// An AI concern the reviewer settled, greyed: made into an issue, leading to it, or dismissed, which can be taken back
function Settled({ note, doing, onShow }: {
  note: ReviewNote;
  doing: ReviewDoing;
  onShow: (note: ReviewNote) => void;
}) {
  const dismissed = note.settled === "dismissed";
  return (
    <Box sx={{ opacity: 0.75, py: 1 }}>
      <Stack direction="row" sx={{ alignItems: "center", gap: 0.75 }}>
        {note.severity && <TagChip tags={[ note.severity ]} category="severity" />}
        <Typography variant="caption" sx={{ color: "text.secondary" }}>
          {dismissed ? "Dismissed" : "Made into an issue"}
        </Typography>
        {dismissed
          ? <Link component="button" variant="caption" onClick={() => void doing.undismiss(note)}>Undo</Link>
          : anchored(note) && <Link component="button" variant="caption" onClick={() => onShow(note)}>Show where</Link>}
      </Stack>
      <Typography variant="body2" sx={{ ...cutShort(2), color: "text.secondary" }}>{note.text}</Typography>
    </Box>
  );
}

// The reviewer's issues, most serious first
function Issues({ issues, dense, about, doing, onShow }: {
  issues: ReviewNote[];
  dense: boolean;
  about: (note: ReviewNote) => string;
  doing: ReviewDoing;
  onShow: (note: ReviewNote) => void;
}) {
  return (
    <Rows>
      {bySeverity(issues).map(issue => (
        <IssueRow key={issue.id} issue={issue} dense={dense} about={about} doing={doing} onShow={onShow} />
      ))}
    </Rows>
  );
}

// An issue under a risk, pointing at no answer or passage
function AddIssue({ topic, doing, prominent = false }: { topic?: string; doing: ReviewDoing; prominent?: boolean }) {
  const [ writing, setWriting ] = useState(false);
  if (!writing) {
    return prominent ? (
      <Button size="small" variant="outlined" startIcon={<AddOutlined />} onClick={() => setWriting(true)}>
        Add an issue
      </Button>
    ) : (
      <Button size="small" variant="text" sx={{ alignSelf: "flex-start", px: 0.75, ml: -0.75 }}
        onClick={() => setWriting(true)}>
        Add an issue
      </Button>
    );
  }
  return (
    // Beside the grid's heading, it opens over the full width under it
    <Box sx={theme => ({ ...passageCard(theme, "draft"), px: 1.25, py: 1, outline: "2px solid",
      ...prominent ? { flexBasis: "100%", order: 1 } : {},
      outlineColor: theme.palette.draft.main, outlineOffset: "-2px" })}>
      <Composer
        kinds={[ "issue" ]} initialTopic={topic ?? ""}
        onCancel={() => setWriting(false)}
        onSave={async (_kind, text, severity, chosen) => {
          await doing.addIssue(chosen === "" ? undefined : chosen, text, severity);
          setWriting(false);
        }}
      />
    </Box>
  );
}

function Unfiled({ unfiled, about, doing, dense = false, onShow }: ReviewProps & {
  dense?: boolean;
  onShow: (note: ReviewNote) => void;
}) {
  if (dense && unfiled.length === 0) {
    return null;
  }
  return (
    <Box sx={dense ? { borderTop: 1, borderColor: "divider", px: 1.5, py: 1 } : undefined}>
      {dense && (
        <Typography variant="caption" sx={{ display: "block", fontWeight: "fontWeightBold", mb: 0.5 }}>
          Your issues under no risk ({unfiled.length})
        </Typography>
      )}
      {!dense && (
        <Typography variant="body2" sx={{ color: "text.secondary", mb: 1 }}>
          Issues not filed under one of your risks. Change one to file it.
        </Typography>
      )}
      <Issues issues={unfiled} dense={dense} about={about} doing={doing} onShow={onShow} />
      {!dense && <AddIssue doing={doing} />}
    </Box>
  );
}

function Others({ others, doing }: { others: OthersRaised[]; doing: ReviewDoing }) {
  return others.map(one => (
    <Typography key={one.group} variant="caption"
      sx={{ display: "block", px: 1.5, py: 1, borderTop: 1, borderColor: "divider", color: "text.secondary" }}>
      {one.group} raised {plural(one.count, "issue")}
      {one.shared > 0 ? `, ${one.shared} on what you also review` : ""}.{" "}
      <Link component="button" variant="caption" onClick={doing.showOthers}>Show them</Link>
    </Typography>
  ));
}

// The decision, and since when it waits on the reviewer
function Decision({ waitingSince, decision, wide = false }: {
  waitingSince?: string;
  decision: ReviewDoing["decision"];
  wide?: boolean;
}) {
  if (!decision) {
    return null;
  }
  return (
    <Stack direction={wide ? { xs: "column", sm: "row" } : "column"} spacing={1}
      sx={{ p: 1.5, borderTop: 1, borderColor: "divider", alignItems: wide ? { sm: "center" } : undefined }}>
      <Typography variant="caption" sx={{ color: "text.secondary", flex: wide ? 1 : undefined }}>
        {waitingSince ? `Waiting on your decision since ${waitingSince}` : "Not waiting on your decision now"}
      </Typography>
      {wide && decision.board && (
        <Button variant="text" size="small" startIcon={<GroupsOutlined />} onClick={decision.board}>
          Escalate to the board
        </Button>
      )}
      <Button variant="outlined" size="small" startIcon={<ForwardToInboxOutlined />} onClick={decision.send}>
        Request changes
      </Button>
      <Tooltip title={decision.approveBlocked ?? ""}>
        <span>
          <Button variant="contained" size="small" fullWidth startIcon={<TaskAltOutlined />}
            disabled={Boolean(decision.approveBlocked)} onClick={decision.approve}>
            Approve
          </Button>
        </span>
      </Tooltip>
      {!wide && decision.board && (
        <Button variant="text" size="small" startIcon={<GroupsOutlined />} onClick={decision.board}>
          Escalate to the board
        </Button>
      )}
    </Stack>
  );
}

// Folded, the panel keeps the count of the reviewer's issues and the decision
function Folded({ issueCount, onToggle, decision }: {
  issueCount: number;
  onToggle: () => void;
  decision: ReviewDoing["decision"];
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
      {decision && (
        <>
          <Tooltip title="Request changes" placement="left">
            <IconButton size="small" aria-label="Request changes" color="primary" onClick={decision.send}>
              <ForwardToInboxOutlined />
            </IconButton>
          </Tooltip>
          <Tooltip title={decision.approveBlocked ?? "Approve"} placement="left">
            <span>
              <IconButton size="small" aria-label="Approve" color="primary" disabled={Boolean(decision.approveBlocked)}
                onClick={decision.approve}>
                <TaskAltOutlined />
              </IconButton>
            </span>
          </Tooltip>
          {decision.board && (
            <Tooltip title="Escalate to the board" placement="left">
              <IconButton size="small" aria-label="Escalate to the board" onClick={decision.board}>
                <GroupsOutlined />
              </IconButton>
            </Tooltip>
          )}
        </>
      )}
    </Paper>
  );
}
