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
import AutoAwesomeOutlined from "@mui/icons-material/AutoAwesomeOutlined";
import CheckCircle from "@mui/icons-material/CheckCircle";
import CircleOutlined from "@mui/icons-material/CircleOutlined";
import CloseFullscreenOutlined from "@mui/icons-material/CloseFullscreenOutlined";
import EditOutlined from "@mui/icons-material/EditOutlined";
import ErrorOutlineOutlined from "@mui/icons-material/ErrorOutlineOutlined";
import ExpandLessOutlined from "@mui/icons-material/ExpandLessOutlined";
import ExpandMoreOutlined from "@mui/icons-material/ExpandMoreOutlined";
import ForwardToInboxOutlined from "@mui/icons-material/ForwardToInboxOutlined";
import GroupsOutlined from "@mui/icons-material/GroupsOutlined";
import MenuBookOutlined from "@mui/icons-material/MenuBookOutlined";
import OpenInFullOutlined from "@mui/icons-material/OpenInFullOutlined";
import RateReviewOutlined from "@mui/icons-material/RateReviewOutlined";
import TaskAltOutlined from "@mui/icons-material/TaskAltOutlined";
import {
  Box, Button, ButtonBase, DialogContentText, FormControlLabel, IconButton, LinearProgress, Link, Stack, Switch,
  Tooltip, Typography,
} from "@mui/material";

import { getCutShortStyle, getPassageCardStyle } from "@iap/documents-pdf-viewer/passageStyles";
import ConfirmActionDialog from "@iap/frontend-commons/components/ConfirmActionDialog";
import { useNarrowerThan } from "@iap/frontend-commons/useNarrowerThan";
import { fadeEdges, useScrollEdges } from "@iap/frontend-commons/useScrollEdges";
import TagChip from "@iap/tags/TagChip";

import AiGrid from "./AiGrid";
import Composer from "./Composer";
import IssuesGrid from "./IssuesGrid";
import LetterPage from "./LetterPage";
import NoteCard, { SentLock, Thread } from "./NoteCard";
import RemoveButton from "./RemoveButton";
import { SEVERITIES, type ReviewNote, type Topic } from "./reviewData";
import { PanelBody } from "./sidePanel";

import type { DraftedLetter } from "./rebLetter";

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
  addNote: (text: string) => Promise<void>;
  editNote: (note: ReviewNote, text: string) => Promise<void>;
  reply: (note: ReviewNote, text: string) => Promise<void>;
  editReply: (reply: string, text: string) => Promise<void>;
  removeReply: (reply: string) => Promise<void>;
  showOthers: () => void;
  decision?: DecisionActions;
}

export interface DecisionActions {
  // Whether the decision waits on the reviewer; when it does not, there is nothing to decide
  waiting: boolean;
  // What the reviewer last decided, as a sentence, and the letter they sent with it, to read again
  decided?: string;
  outcome?: string;
  readLetter?: () => void;
  send: () => void;
  approve: () => void;
  approveBlocked?: string;
  board?: () => void;
}

interface ReviewProps {
  risks: RiskReview[];
  unfiled: ReviewNote[];
  // The reviewer's notes to self on the submission as a whole
  notes: ReviewNote[];
  issueCount: number;
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

// From how wide the review lays out its risks down the side, the one chosen beside them, rather than one under another
const WIDE = 700;

// PRETOTYPE. The reviewer's review, in a tab of the panel beside whatever they are reading, as the risks their group
// weighs: for each, what the schema says to look at, what the AI pre-review made of it and everything it raised under
// it, the answers the protocol contradicts, the reviewer's own issues, and whether they have considered it, counted at
// the top. Issues can be added under a risk without pointing at an answer or a passage; the AI's concerns made issues
// of or dismissed, and dismissals taken back. Narrow, the risks open one at a time, one under another; given the room,
// dragged wider or over the whole workspace, they are listed down the side, with what cuts across them, the AI
// pre-review and all the reviewer's issues, as tables, the one chosen read in full beside them. At the bottom, the
// decision.
export default function ReviewPanel(props: ReviewProps & {
  // Whether it is shown over the whole workspace, rather than beside the page
  full: boolean;
  onFull: (full: boolean) => void;
  // The letter asking for changes, while the reviewer is on it, shown beside their issues over the whole workspace
  letter?: LetterWriting;
}) {
  const { risks, doing, full, onFull, letter } = props;
  // The risk opened or, laid out wide, the page chosen, kept from one layout to the other
  const [ chosen, setChosen ] = useState<string>();
  const [ root, setRoot ] = useState<HTMLDivElement | null>(null);
  const compact = useNarrowerThan(root, WIDE);
  const wide = full || !compact;
  // Showing where something was raised brings the page back beside the review
  const show = (note: ReviewNote) => {
    onFull(false);
    doing.open(note);
  };

  return (
    <Box ref={setRoot} sx={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>
      <Stack direction="row" sx={{ alignItems: "center", gap: 2, pl: 1.5, pr: 0.5, py: 1,
        ...wide ? { borderBottom: 1, borderColor: "divider" } : {} }}>
        {/* Over the workspace, where the tabs naming it are hidden */}
        {full && !compact && <Typography variant="panelTitle">{letter ? "Letter to the study team" : "Review"}</Typography>}
        {letter ? <Box sx={{ flex: 1 }} /> : (
          <>
            <Box sx={{ flex: 1, maxWidth: wide ? 560 : undefined }}><Progress risks={risks} more={wide} /></Box>
            {wide && <Box sx={{ flex: 1 }} />}
          </>
        )}
        <Tooltip disableInteractive title={full ? "Back beside the page" : "Open over the whole workspace"}
          placement="left">
          <IconButton size="small" aria-label={full ? "Show the review beside the page" : "Show the review over the whole workspace"}
            onClick={() => onFull(!full)}>
            {full ? <CloseFullscreenOutlined fontSize="small" /> : <OpenInFullOutlined fontSize="small" />}
          </IconButton>
        </Tooltip>
      </Stack>
      {letter && full ? <LetterView {...props} letter={letter} row={!compact} onShow={show} />
        : wide ? <WideReview {...props} chosen={chosen} onChosen={setChosen} row={!compact} roomy={full}
          onShow={show} /> : (
          <PanelBody>
            {risks.map(risk => (
              <Box key={risk.topic.path} sx={{ borderTop: 1, borderColor: "divider" }}>
                <RiskHeading
                  risk={risk} doing={doing} open={chosen === risk.topic.path}
                  onClick={() => setChosen(chosen === risk.topic.path ? undefined : risk.topic.path)}
                />
                {chosen === risk.topic.path && (
                  <Box sx={{ pl: 5, pr: 1.5, pb: 1.25 }}>
                    <RiskDetail risk={risk} dense about={props.about} doing={doing} onShow={show} />
                  </Box>
                )}
              </Box>
            ))}
            <Unfiled {...props} dense onShow={show} />
            <Others others={props.others} doing={doing} />
            <SelfNotes notes={props.notes} doing={doing} />
          </PanelBody>
        )}
      {!(letter && full) && <Decision waitingSince={props.waitingSince} decision={doing.decision} wide={wide} />}
    </Box>
  );
}

// The letter asking for changes, drawn from the reviewer's issues, and what can be done with it
export interface LetterWriting {
  draft: DraftedLetter;
  send: () => Promise<void>;
  close: () => void;
}

// The letter beside the issues it says, in its order, under its headings: an issue changed is the letter changed. A
// point clicked in the letter selects its issue, and an issue clicked its point. Sending it, after making sure,
// asks the study team for changes
function LetterView(props: ReviewProps & {
  letter: LetterWriting;
  row: boolean;
  onShow: (note: ReviewNote) => void;
}) {
  const { letter, doing, row } = props;
  const [ selected, setSelected ] = useState<string>();
  const [ confirming, setConfirming ] = useState(false);
  const issues = new Map([ ...props.risks.flatMap(one => one.issues), ...props.unfiled ]
    .map(issue => [ issue.id, issue ]));
  const select = (id: string, show: string) => {
    setSelected(id);
    document.getElementById(show)?.scrollIntoView({ block: "nearest", behavior: "smooth" });
  };
  const points = letter.draft.sections.flatMap(section => section.points).length;
  return (
    <>
      {/* The issues and the page side by side, together in the middle of whatever room is left over */}
      <Stack direction={row ? "row" : "column"} sx={{ flex: 1, minHeight: 0, justifyContent: "center" }}>
        <Box sx={{ flex: row ? "1 1 0" : undefined, minWidth: 0, maxWidth: row ? 640 : undefined, overflowY: "auto",
          px: 2, py: 1.5 }}>
          <Typography variant="body2" sx={{ color: "text.secondary", mb: 2 }}>
            The letter is drawn from your open issues. To change what it says, change your issues here.
          </Typography>
          {points === 0 && (
            <Typography variant="body2" sx={{ color: "text.secondary" }}>
              {"You haven't raised any issues for the study team, so the letter has no points."}
            </Typography>
          )}
          {letter.draft.sections.map(section => (
            <Box key={section.heading} sx={{ mb: 2 }}>
              <Typography variant="subtitle2" component="h3" sx={{ fontWeight: "fontWeightBold", mb: 0.5 }}>
                {section.heading}
              </Typography>
              {section.points.map(point => {
                const issue = issues.get(point.id);
                return issue && (
                  <Stack key={point.id} direction="row" onClick={() => select(point.id, `letter-point-${point.id}`)}
                    sx={theme => ({ gap: 1, px: 1, mx: -1, mb: 0.5, borderRadius: 1, cursor: "pointer",
                      bgcolor: point.id === selected ? theme.alpha(theme.palette.issue.main, 0.12) : "background.paper",
                      outline: point.id === selected ? `2px solid ${theme.palette.issue.main}` : "none" })}>
                    <Typography variant="body2" sx={{ pt: 1.25, fontWeight: "fontWeightBold", minWidth: 20 }}>
                      {point.number}.
                    </Typography>
                    <Box sx={{ flex: 1, minWidth: 0 }}>
                      <IssueRow issue={issue} dense={false} about={props.about} doing={doing} onShow={props.onShow} />
                    </Box>
                  </Stack>
                );
              })}
            </Box>
          ))}
        </Box>
        <Box sx={{ flex: row ? "0 1 880px" : undefined, minWidth: 0, overflowY: "auto", p: { xs: 1, sm: 3 },
          pl: { sm: row ? 1 : 3 } }}>
          <LetterPage letter={letter.draft} selected={selected}
            onSelect={id => select(id, `review-issue-${id}`)} />
        </Box>
      </Stack>
      <Stack direction={{ xs: "column", sm: "row" }} spacing={1}
        sx={{ p: 1.5, borderTop: 1, borderColor: "divider", alignItems: { sm: "center" } }}>
        <Typography variant="caption" sx={{ color: "text.secondary", flex: 1 }}>
          Sending it asks the study team for changes, and keeps your issues as they read them.
        </Typography>
        <Button size="small" onClick={letter.close}>Back to the review</Button>
        <Button variant="contained" size="small" startIcon={<ForwardToInboxOutlined />}
          onClick={() => setConfirming(true)}>
          Send and request changes
        </Button>
      </Stack>
      {confirming && (
        <ConfirmActionDialog
          title="Send the letter to the study team?" confirmLabel="Send" workingLabel="Sending…"
          onConfirm={letter.send} onClose={() => setConfirming(false)}
        >
          <DialogContentText>
            {"It asks them for changes, and pauses the clock until they answer. Your issues in it can't be changed "
              + "after, only replied to."}
          </DialogContentText>
        </ConfirmActionDialog>
      )}
    </>
  );
}

// The review laid out wide: the risks down the side, after what cuts across them, the AI pre-review and the reviewer's
// issues; the one chosen read in full beside them, a risk with what its schema says to look at. Too narrow for that,
// as on a phone, the list goes above the page
function WideReview(props: ReviewProps & {
  chosen: string | undefined;
  onChosen: (page: string) => void;
  row: boolean;
  // Whether it has the whole workspace, which gives the list more room
  roomy: boolean;
  onShow: (note: ReviewNote) => void;
}) {
  const { risks, doing, row, onShow } = props;
  const [ sideElement, setSideElement ] = useState<HTMLDivElement | null>(null);
  const sideEdges = useScrollEdges(sideElement);
  // Whether the AI pre-review found anything: it may have failed, or the submission left it nothing to say
  const aiFound = risks.some(one => Boolean(one.assessment) || one.aiOpen.length > 0 || one.mismatches.length > 0
    || one.aiSettled.length > 0);
  // Until the reviewer has chosen a page: what the AI found while they have raised nothing yet, to triage first; their
  // issues once they have, or when the AI found nothing
  const chosen = props.chosen ?? (aiFound && props.issueCount === 0 ? AI_TAB : ALL_ISSUES);
  const setChosen = props.onChosen;
  const [ grouped, setGrouped ] = useState(false);
  const [ withSettled, setWithSettled ] = useState(false);
  const openFindings = risks.reduce((sum, one) => sum + one.aiOpen.length, 0)
    + new Set(risks.flatMap(one => one.mismatches.map(mismatch => mismatch.evidence.id))).size;
  const risk = risks.find(one => one.topic.path === chosen);
  const entrySx = (page: string) => ({ display: "flex", justifyContent: "flex-start", gap: 0.75, width: "100%",
    textAlign: "start", px: 2, py: 1, borderTop: 1, borderColor: "divider",
    bgcolor: chosen === page ? "background.paper" : undefined }) as const;
  return (
    <Stack direction={row ? "row" : "column"} sx={{ flex: 1, minHeight: 0 }}>
      {/* No scrollbar between the list and the page beside it, as in the contents panel: the fade says there is more */}
      <Box ref={setSideElement} sx={{ width: row ? (props.roomy ? 340 : 280) : undefined, flexShrink: 0,
        overflowY: "auto", maxHeight: row ? undefined : 240, scrollbarWidth: "none", maskImage: fadeEdges(sideEdges) }}>
        <ButtonBase onClick={() => setChosen(AI_TAB)} sx={{ ...entrySx(AI_TAB), borderTop: "none" }}>
          <AutoAwesomeOutlined sx={{ fontSize: "1rem", color: "assistant.main" }} />
          <Typography variant="body2" sx={{ color: "primary.main", fontWeight: "fontWeightMedium" }}>
            AI pre-review ({openFindings})
          </Typography>
        </ButtonBase>
        <ButtonBase onClick={() => setChosen(ALL_ISSUES)} sx={entrySx(ALL_ISSUES)}>
          <RateReviewOutlined sx={{ fontSize: "1rem", color: "issue.main" }} />
          <Typography variant="body2" sx={{ color: "primary.main", fontWeight: "fontWeightMedium" }}>
            Your issues ({props.issueCount})
          </Typography>
        </ButtonBase>
        {risks.map(one => (
          <Box key={one.topic.path} sx={{ borderTop: 1, borderColor: "divider",
            bgcolor: chosen === one.topic.path ? "background.paper" : undefined }}>
            <RiskHeading risk={one} doing={doing} onClick={() => setChosen(one.topic.path)} />
          </Box>
        ))}
        <ButtonBase onClick={() => setChosen(UNFILED)} sx={{ ...entrySx(UNFILED), display: "block" }}>
          <Typography variant="body2" sx={{ color: "primary.main", fontWeight: "fontWeightMedium" }}>
            Under no risk
          </Typography>
          <Typography variant="caption" sx={{ color: "text.secondary" }}>
            {plural(props.unfiled.length, "issue")}
          </Typography>
        </ButtonBase>
        <Others others={props.others} doing={doing} />
        <SelfNotes notes={props.notes} doing={doing} />
      </Box>
      <Box sx={{ flex: 1, minWidth: 0, overflowY: "auto", px: { xs: 2, md: 3 }, py: 2.5, containerType: "inline-size",
        bgcolor: "background.paper" }}>
        {risk && (
          <Stack spacing={2}>
            <Stack direction="row" sx={{ alignItems: "center", gap: 1 }}>
              <Typography variant="h6" component="h3" sx={{ flex: 1 }}>{risk.topic.label}</Typography>
              <ConsideredButton risk={risk} doing={doing} />
            </Stack>
            <RiskDetail risk={risk} about={props.about} doing={doing} onShow={onShow} />
          </Stack>
        )}
        {chosen === AI_TAB && (
          <Stack spacing={2} sx={{ height: "100%", minHeight: 360 }}>
            <Stack direction="row" sx={{ alignItems: "center", gap: 2, flexWrap: "wrap" }}>
              <AutoAwesomeOutlined sx={{ color: "assistant.main" }} />
              <Typography variant="h6" component="h3" sx={{ flex: 1 }}>AI pre-review</Typography>
              <FormControlLabel label="Group by risk"
                control={<Switch checked={grouped} onChange={event => setGrouped(event.target.checked)} />} />
              <FormControlLabel label="Show settled"
                control={<Switch checked={withSettled} onChange={event => setWithSettled(event.target.checked)} />} />
            </Stack>
            <Box sx={{ flex: 1, minHeight: 0 }}>
              <AiGrid
                risks={risks} grouped={grouped} settled={withSettled} about={props.about} onShow={onShow}
                onMakeIssue={concern => doing.makeIssue(concern,
                  concern.suggestion ? `${concern.text}\n\n${concern.suggestion}` : concern.text,
                  concern.severity, concern.topicPath)}
                onDismiss={concern => doing.dismiss(concern)} onUndismiss={concern => doing.undismiss(concern)}
              />
            </Box>
          </Stack>
        )}
        {chosen === ALL_ISSUES && (
          <IssueTable
            fill issues={[ ...risks.flatMap(one => one.issues), ...props.unfiled ]}
            risks={risks.map(one => ({ path: one.topic.path, label: one.topic.label }))}
            about={props.about} doing={doing} onShow={onShow}
            heading={<>
              <RateReviewOutlined sx={{ color: "issue.main" }} />
              <Typography variant="h6" component="h3" sx={{ flex: 1 }}>Your issues</Typography>
            </>}
          />
        )}
        {chosen === UNFILED && (
          // The grid of all the reviewer's issues, narrowed to these, its Risk kept to file them
          <IssueTable
            compact keepRisk issues={props.unfiled}
            risks={risks.map(one => ({ path: one.topic.path, label: one.topic.label }))}
            about={props.about} doing={doing} onShow={onShow}
            heading={<Typography variant="h6" component="h3" sx={{ flex: 1 }}>Under no risk</Typography>}
            note="Issues not filed under one of your risks. Choose a risk in the Risk column to file one there."
          />
        )}
      </Box>
    </Stack>
  );
}

const UNFILED = "unfiled";
const ALL_ISSUES = "all";
const AI_TAB = "ai";

// How far the review has gone: the risks considered, and, given room, what is left open across them
function Progress({ risks, more = false }: { risks: RiskReview[]; more?: boolean }) {
  const considered = risks.filter(risk => risk.considered).length;
  const concerns = risks.reduce((sum, one) => sum + one.aiOpen.length, 0);
  // An answer two risks draw on counts once
  const contradictions = new Set(risks.flatMap(one => one.mismatches.map(mismatch => mismatch.evidence.id))).size;
  return (
    <>
      <Typography variant="caption" sx={{ display: "block" }}>
        {considered} of {plural(risks.length, "risk")} considered
        {more && concerns > 0 && ` · ${plural(concerns, "open AI concern")}`}
        {more && contradictions > 0 && ` · ${plural(contradictions, "contradiction")}`}
      </Typography>
      <LinearProgress
        variant="determinate" value={risks.length ? considered / risks.length * 100 : 0}
        aria-label="Risks considered" sx={{ mt: 0.5, borderRadius: 1 }}
      />
    </>
  );
}


function ConsideredButton({ risk, doing }: { risk: RiskReview; doing: ReviewDoing }) {
  const [ saving, setSaving ] = useState(false);
  return (
    <Tooltip disableInteractive title={risk.considered ? "Considered. Click to unmark" : "Mark as considered"} placement="left">
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
  const aiSaid = Boolean(risk.assessment) || risk.mismatches.length > 0 || risk.aiOpen.length > 0
    || risk.aiSettled.length > 0;
  // From the app: what the schema says to look at for this risk
  const guidance = risk.topic.description && <Guidance text={risk.topic.description} dense={dense} />;
  // From the AI, all in its own frame: its read, the answers it read the protocol to contradict, what it raised
  const ai = aiSaid && (
    <AiBlock dense={dense}>
      {risk.assessment && <Typography variant={dense ? "caption" : "body2"}>{risk.assessment}</Typography>}
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
        <Section title="Concerns" dense={dense}>
          <Rows>
            {bySeverity(risk.aiOpen).map(note => (
              <AiRow key={note.id} note={note} topic={risk.topic.path} dense={dense} about={about} doing={doing}
                onShow={onShow} />
            ))}
          </Rows>
        </Section>
      )}
      {risk.aiSettled.length > 0 && (
        <SettledConcerns concerns={risk.aiSettled} dense={dense} doing={doing} onShow={onShow} />
      )}
    </AiBlock>
  );
  // The reviewer's own
  const yours = (
    <Section title={`Your issues (${risk.issues.length})`} dense={dense}>
      <Issues issues={risk.issues} dense={dense} about={about} doing={doing} onShow={onShow} />
      <AddIssue topic={risk.topic.path} doing={doing} />
    </Section>
  );
  if (dense) {
    return (
      <Stack spacing={1.25}>
        {guidance}
        {ai}
        {yours}
      </Stack>
    );
  }
  // In full, what the reviewer reads first, the app's guidance beside the AI's pre-review where the room allows; then,
  // the whole width, what they write: their issues under the risk, in the grid of all their issues, narrowed to it
  return (
    <Stack spacing={3}>
      <Box sx={{ display: "grid", gridTemplateColumns: "minmax(0, 1fr)", gap: 3, alignItems: "start",
        "@container (min-width: 900px)": { gridTemplateColumns: "minmax(0, 5fr) minmax(0, 7fr)", columnGap: 4 } }}>
        {guidance ?? <Box />}
        {ai || <Box />}
      </Box>
      <IssueTable
        compact issues={risk.issues} risks={[ { path: risk.topic.path, label: risk.topic.label } ]}
        defaultRisk={risk.topic.path} about={about} doing={doing} onShow={onShow}
        heading={(
          <Typography variant="subtitle2" component="h4" sx={{ fontWeight: "fontWeightBold", flex: 1 }}>
            Your issues ({risk.issues.length})
          </Typography>
        )}
      />
    </Stack>
  );
}

// The reviewer's issues as a table under a heading, with a way to add one in a row of its own at the table's top;
// filling the height it is given, or as tall as its rows
function IssueTable({ heading, note, issues, risks, defaultRisk, compact = false, keepRisk = false, fill = false,
  about, doing, onShow }: {
  heading: ReactNode;
  note?: string;
  issues: ReviewNote[];
  risks: { path: string; label: string }[];
  defaultRisk?: string;
  compact?: boolean;
  keepRisk?: boolean;
  fill?: boolean;
  about: (note: ReviewNote) => string;
  doing: ReviewDoing;
  onShow: (note: ReviewNote) => void;
}) {
  const [ adding, setAdding ] = useState(false);
  const grid = (
    <IssuesGrid
      compact={compact} keepRisk={keepRisk} issues={issues} risks={risks} about={about} onShow={onShow}
      onRemove={issue => doing.removeIssue(issue)} onReply={(issue, text) => doing.reply(issue, text)}
      onEditReply={(reply, text) => doing.editReply(reply, text)} onRemoveReply={reply => doing.removeReply(reply)}
      onChange={(issue, text, severity, topic) => doing.editIssue(issue, text, severity, topic)}
      adding={adding} defaultRisk={defaultRisk} onAddingDone={() => setAdding(false)}
      onAdd={(text, severity, topic) => doing.addIssue(topic || undefined, text, severity)}
    />
  );
  return (
    <Stack spacing={fill ? 2 : 1} sx={fill ? { height: "100%", minHeight: 360 } : undefined}>
      <Stack direction="row" sx={{ alignItems: "center", gap: 2, flexWrap: "wrap" }}>
        {heading}
        <Button size="small" variant="contained" startIcon={<AddOutlined />} disabled={adding}
          onClick={() => setAdding(true)}>
          Add an issue
        </Button>
      </Stack>
      {note && <Typography variant="body2" sx={{ color: "text.secondary" }}>{note}</Typography>}
      {fill ? <Box sx={{ flex: 1, minHeight: 0 }}>{grid}</Box> : grid}
    </Stack>
  );
}

// The AI's concerns the reviewer has settled, folded under a line saying how many, since they are history
function SettledConcerns({ concerns, dense, doing, onShow }: {
  concerns: ReviewNote[];
  dense: boolean;
  doing: ReviewDoing;
  onShow: (note: ReviewNote) => void;
}) {
  const [ open, setOpen ] = useState(false);
  return (
    <Box>
      <Link component="button" variant="caption" onClick={() => setOpen(!open)} aria-expanded={open}
        sx={{ display: "inline-flex", alignItems: "center", gap: 0.25 }}>
        {open ? <ExpandLessOutlined fontSize="small" /> : <ExpandMoreOutlined fontSize="small" />}
        {plural(concerns.length, "settled concern")}
      </Link>
      {open && (
        <Rows>
          {bySeverity(concerns).map(note => (
            <Settled key={note.id} note={note} dense={dense} doing={doing} onShow={onShow} />
          ))}
        </Rows>
      )}
    </Box>
  );
}

// What the app says to look at for a risk, from its schema: reference, on a quiet surface, its first paragraph
// shown and the rest a click away; in the panel, a single line that opens it
function Guidance({ text, dense }: { text: string; dense: boolean }) {
  const [ open, setOpen ] = useState(false);
  const [ first, ...rest ] = text.split(/\n\s*\n/);
  return (
    <Box sx={{ bgcolor: "background.muted", borderRadius: 1, px: dense ? 1 : 2, py: dense ? 0.75 : 1.5 }}>
      <Stack direction="row" sx={{ alignItems: "center", gap: 0.75, mb: dense && !open ? 0 : 0.5 }}>
        <MenuBookOutlined sx={{ fontSize: "1rem", color: "text.secondary" }} />
        <Typography variant="caption" component="h4" sx={{ fontWeight: "fontWeightBold", color: "text.secondary",
          flex: 1 }}>
          What to look at
        </Typography>
        {(dense || rest.length > 0) && (
          <Link component="button" variant="caption" onClick={() => setOpen(!open)}>{open ? "Less" : "More"}</Link>
        )}
      </Stack>
      {(!dense || open) && (
        <Typography variant={dense ? "caption" : "body2"} component="div"
          sx={{ whiteSpace: "pre-wrap", color: "text.secondary" }}>
          {open ? text : first}
        </Typography>
      )}
    </Box>
  );
}

// What the AI pre-review says, in its frame: its colour down the side, its mark and its name, so nothing it says
// reads as the app's or the reviewer's
export function AiBlock({ dense = false, title = "AI pre-review", children }: {
  dense?: boolean;
  title?: string;
  children: ReactNode;
}) {
  return (
    <Stack spacing={dense ? 1 : 1.5} sx={theme => ({ borderInlineStart: "3px solid", borderColor: "assistant.main",
      bgcolor: theme.alpha(theme.palette.assistant.light, 0.12), borderRadius: 1, px: dense ? 1 : 2,
      py: dense ? 0.75 : 1.5 })}>
      <Stack direction="row" sx={{ alignItems: "center", gap: 0.75 }}>
        <AutoAwesomeOutlined sx={{ fontSize: "1rem", color: "assistant.main" }} />
        <Typography variant="caption" component="h4" sx={{ fontWeight: "fontWeightBold", color: "assistant.main" }}>
          {title}
        </Typography>
      </Stack>
      {children}
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
          <Typography variant="body2" sx={dense ? getCutShortStyle(3) : undefined}>{note.text}</Typography>
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
  const [ replying, setReplying ] = useState(false);
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
  const replies = issue.replies.length;
  return (
    <Box id={`review-issue-${issue.id}`} sx={{ py: 1, px: 1, mx: -1, borderRadius: 1, scrollMarginTop: 16 }}>
      <RowHead note={issue} about={about} tools={issue.sent ? <SentLock sent={issue.sent} /> : (
        <Box sx={{ display: "flex", mr: -0.75 }}>
          <Tooltip disableInteractive title="Change">
            <IconButton size="small" aria-label="Change" onClick={() => setEditing(true)}>
              <EditOutlined fontSize="small" />
            </IconButton>
          </Tooltip>
          <RemoveButton what="issue" text={issue.text} onRemove={() => doing.removeIssue(issue)} />
        </Box>
      )} />
      <Typography variant="body2" sx={{ whiteSpace: "pre-wrap", ...dense ? getCutShortStyle(3) : {} }}>{issue.text}</Typography>
      {/* In full, its thread under it: the study team's and other reviewers' replies, and the reviewer's own */}
      {!dense && (
        <Thread replies={issue.replies} onEdit={(reply, text) => doing.editReply(reply, text)}
          onRemove={reply => doing.removeReply(reply)} />
      )}
      {replying && (
        <Box sx={{ mt: 1 }}>
          <Composer
            kinds={[]} saveLabel="Reply" onCancel={() => setReplying(false)}
            onSave={async (_kind, text) => {
              await doing.reply(issue, text);
              setReplying(false);
            }}
          />
        </Box>
      )}
      <Links>
        {dense && replies > 0 && (
          <Typography variant="caption" sx={{ color: "text.secondary" }}>
            {replies === 1 ? "1 reply" : `${replies} replies`}
          </Typography>
        )}
        {!dense && !replying && (
          <Link component="button" variant="caption" onClick={() => setReplying(true)}>Reply</Link>
        )}
        {anchored(issue) && <Link component="button" variant="caption" onClick={() => onShow(issue)}>Show where</Link>}
      </Links>
    </Box>
  );
}


// An AI concern the reviewer settled, greyed: made into an issue, leading to it, or dismissed, which can be taken back
function Settled({ note, dense, doing, onShow }: {
  note: ReviewNote;
  dense: boolean;
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
      <Typography variant="body2" sx={{ ...dense ? getCutShortStyle(2) : {}, color: "text.secondary" }}>{note.text}</Typography>
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

// An issue under a risk, pointing at no answer or passage, written in the panel
function AddIssue({ topic, doing }: { topic?: string; doing: ReviewDoing }) {
  const [ writing, setWriting ] = useState(false);
  if (!writing) {
    return (
      <Button size="small" variant="text" sx={{ alignSelf: "flex-start", px: 0.75, ml: -0.75 }}
        onClick={() => setWriting(true)}>
        Add an issue
      </Button>
    );
  }
  return (
    <Box sx={theme => ({ ...getPassageCardStyle(theme, "draft"), px: 1.25, py: 1, outline: "2px solid",
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

// The reviewer's notes to self on the submission as a whole, the ones the queue shows beside it
function SelfNotes({ notes, doing }: { notes: ReviewNote[]; doing: ReviewDoing }) {
  const [ writing, setWriting ] = useState(false);
  return (
    <Stack spacing={1} sx={{ borderTop: 1, borderColor: "divider", px: 1.5, py: 1 }}>
      <Typography variant="caption" sx={{ fontWeight: "fontWeightBold" }}>
        Notes to self{notes.length > 0 ? ` (${notes.length})` : ""}
      </Typography>
      {notes.map(note => (
        <Box key={note.id} sx={theme => ({ ...getPassageCardStyle(theme, "annotation"), px: 1.25, py: 0.75 })}>
          <NoteCard
            note={note} onEdit={said => doing.editNote(note, said)} onRemove={() => doing.removeIssue(note)}
            onMakeIssue={(said, severity, topic) => doing.makeIssue(note, said, severity, topic)}
          />
        </Box>
      ))}
      {writing ? (
        <Box sx={theme => ({ ...getPassageCardStyle(theme, "draft"), px: 1.25, py: 1, outline: "2px solid",
          outlineColor: theme.palette.draft.main, outlineOffset: "-2px" })}>
          <Composer
            kinds={[ "note" ]}
            onCancel={() => setWriting(false)}
            onSave={async (_kind, said) => {
              await doing.addNote(said);
              setWriting(false);
            }}
          />
        </Box>
      ) : (
        <Button size="small" variant="text" sx={{ alignSelf: "flex-start", px: 0.75, ml: -0.75 }}
          onClick={() => setWriting(true)}>
          Add a note to self
        </Button>
      )}
    </Stack>
  );
}

// What the reviewer decided, when it no longer waits on them, with the letter they sent
function Decided({ decision, wide }: { decision: DecisionActions; wide: boolean }) {
  return (
    <Typography variant="caption" sx={{ color: "text.secondary", flex: wide ? 1 : undefined }}>
      {decision.decided ?? "Not waiting on your decision now."}
      {decision.readLetter && (
        <>
          {" "}
          <Link component="button" variant="caption" onClick={decision.readLetter}>Read your letter</Link>
        </>
      )}
    </Typography>
  );
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
  if (!decision.waiting) {
    return (
      <Box sx={{ p: 1.5, borderTop: 1, borderColor: "divider" }}>
        <Decided decision={decision} wide={wide} />
      </Box>
    );
  }
  return (
    <Stack direction={wide ? { xs: "column", sm: "row" } : "column"} spacing={1}
      sx={{ p: 1.5, borderTop: 1, borderColor: "divider", alignItems: wide ? { sm: "center" } : undefined }}>
      {/* The whole review says since when the decision has waited; the panel keeps to the decision */}
      {/* Back from the study team, it says what was asked of them */}
      {!wide && decision.decided && <Decided decision={decision} wide={false} />}
      {wide && (decision.decided ? <Decided decision={decision} wide /> : (
        <Typography variant="caption" sx={{ color: "text.secondary", flex: 1 }}>
          {waitingSince ? `Waiting on your decision since ${waitingSince}` : "Not waiting on your decision now"}
        </Typography>
      ))}
      {wide && decision.board && (
        <Button variant="text" size="small" startIcon={<GroupsOutlined />} onClick={decision.board}>
          Escalate to the board
        </Button>
      )}
      <Button variant="outlined" size="small" startIcon={<ForwardToInboxOutlined />} onClick={decision.send}>
        Request changes
      </Button>
      <Tooltip disableInteractive title={decision.approveBlocked ?? ""}>
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
