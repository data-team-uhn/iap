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

import { useEffect, useState, type ReactNode } from "react";

import AddOutlined from "@mui/icons-material/AddOutlined";
import ArrowDownwardOutlined from "@mui/icons-material/ArrowDownwardOutlined";
import ArrowUpwardOutlined from "@mui/icons-material/ArrowUpwardOutlined";
import AutoAwesomeOutlined from "@mui/icons-material/AutoAwesomeOutlined";
import CheckOutlined from "@mui/icons-material/CheckOutlined";
import ChevronRightOutlined from "@mui/icons-material/ChevronRightOutlined";
import CloseFullscreenOutlined from "@mui/icons-material/CloseFullscreenOutlined";
import EditOutlined from "@mui/icons-material/EditOutlined";
import ExpandLessOutlined from "@mui/icons-material/ExpandLessOutlined";
import ExpandMoreOutlined from "@mui/icons-material/ExpandMoreOutlined";
import ForwardToInboxOutlined from "@mui/icons-material/ForwardToInboxOutlined";
import GroupsOutlined from "@mui/icons-material/GroupsOutlined";
import InfoOutlined from "@mui/icons-material/InfoOutlined";
import MenuBookOutlined from "@mui/icons-material/MenuBookOutlined";
import OpenInFullOutlined from "@mui/icons-material/OpenInFullOutlined";
import RateReviewOutlined from "@mui/icons-material/RateReviewOutlined";
import TaskAltOutlined from "@mui/icons-material/TaskAltOutlined";
import {
  Box, Button, ButtonBase, Checkbox, FormControlLabel, IconButton, LinearProgress, Link, Stack,
  Switch, Tooltip, Typography,
} from "@mui/material";

import { getCutShortStyle } from "@iap/documents-pdf-viewer/passageStyles";
import { useNarrowerThan } from "@iap/frontend-commons/useNarrowerThan";
import { fadeEdges, useScrollEdges } from "@iap/frontend-commons/useScrollEdges";
import { useStoredSetting } from "@iap/frontend-commons/useStoredSetting";
import TagChip from "@iap/tags/TagChip";

import AiGrid from "./AiGrid";
import Composer from "./Composer";
import IssuesGrid from "./IssuesGrid";
import LetterPage from "./LetterPage";
import { SentLock, Thread } from "./NoteCard";
import NoteKindIcon, { KindCount } from "./noteIcons";
import { pulseWhenThere } from "./pulse";
import RemoveButton from "./RemoveButton";
import { severityShown, SEVERITIES, type Nature, type NoteKind, type ReviewNote, type Topic } from "./reviewData";
import { FOLD_SIZE, FoldButton, PanelBody } from "./sidePanel";

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
  makeIssue: (note: ReviewNote, text: string, severity?: string, topic?: string, nature?: Nature) => Promise<void>;
  dismiss: (note: ReviewNote) => Promise<void>;
  undismiss: (note: ReviewNote) => Promise<void>;
  addIssue: (topic: string | undefined, text: string, severity?: string, nature?: Nature) => Promise<void>;
  editIssue: (note: ReviewNote, text: string, severity?: string, topic?: string, nature?: Nature) => Promise<void>;
  // PRETOTYPE: what the reviewer's new issues are unless they say otherwise, as their group usually asks
  defaultNature: Nature;
  removeIssue: (note: ReviewNote) => Promise<void>;
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
  // PRETOTYPE: what asking the study team is called, a request for changes or a message, and deciding, approving or,
  // for a group consulting for the approving ones, finishing its review
  sendLabel: string;
  // Why there is nothing to send, as when every issue is resolved
  sendBlocked?: string;
  approveLabel: string;
  board?: () => void;
  // PRETOTYPE: why no decision can be taken from here now, as while reading an older version
  blocked?: string;
}

interface ReviewProps {
  risks: RiskReview[];
  unfiled: ReviewNote[];
  issueCount: number;
  // The reviewer group whose risks these are, by its short name; none when the reviewer weighs several groups' risks
  group?: string;
  others: OthersRaised[];
  about: (note: ReviewNote) => string;
  doing: ReviewDoing;
}

// Most serious first; what has no severity, as a question or a suggestion, after
const rankOf = (note: ReviewNote) => {
  const at = SEVERITIES.indexOf(severityShown(note) as typeof SEVERITIES[number]);
  return at < 0 ? SEVERITIES.length : at;
};
const bySeverity = (notes: ReviewNote[]) => notes.toSorted((one, other) => rankOf(one) - rankOf(other));

const plural = (count: number, one: string, many = `${one}s`) => `${count} ${count === 1 ? one : many}`;

const anchored = (note: ReviewNote) => note.subjects.length > 0 || Boolean(note.passage);

// What is under a risk, kind by kind, as the contents count what is in a part: the AI's open concerns, the answers the
// protocol contradicts, the reviewer's issues; and the same said in words
function countsOf(risk: RiskReview): { kind: NoteKind; count: number }[] {
  return ([ [ "aiIssue", risk.aiOpen.length ], [ "contradiction", risk.mismatches.length ],
    [ "issue", risk.issues.length ] ] as const).filter(([ , count ]) => count > 0).map(([ kind, count ]) => ({ kind, count }));
}
const COUNTED: Record<string, [ string, string ]> = { aiIssue: [ "open AI concern", "open AI concerns" ],
  contradiction: [ "AI-found mismatch", "AI-found mismatches" ], issue: [ "issue", "issues" ] };

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
  // Whether it heads itself with the way to show it over the whole workspace or back, rather than its tab doing so
  headed?: boolean;
  // PRETOTYPE: the REB's Letter of Review, drawn from the reviewer's issues, an entry of the review of its own; none
  // for the groups that send their issues without one
  letter?: LetterWriting;
  // PRETOTYPE: what a mark on the page led to, by note path, to open where the review lists it and point it out
  reveal?: { id: string; at: number };
  // Where the entry opened is kept in the browser, for the review to open on it again, such as one per submission
  storedAs: string;
  // PRETOTYPE: whether the contents are folded away, laid out side by side; and whether that leaves the review beside the
  // page saying where it is itself, for its tabs to go
  listFolded: boolean;
  onListFolded: (folded: boolean) => void;
  onFocusing?: (focusing: boolean) => void;
}) {
  const { risks, doing, full, onFull, letter, reveal, onFocusing } = props;
  // The risk opened or, laid out wide, the page chosen, kept from one layout to the other, and from one visit to the
  // next; one no longer there, such as a risk the schema dropped, is forgotten
  const [ stored, setStored ] = useStoredSetting<string | null>(props.storedAs, null, isEntry);
  const known = [ AI_TAB, ALL_ISSUES, UNFILED, ...letter ? [ LETTER ] : [], ...risks.map(one => one.topic.path) ];
  const chosen = stored !== null && known.includes(stored) ? stored : undefined;
  const setChosen = (entry: string | undefined) => setStored(entry ?? null);
  // A mark followed opens the risk the review lists it under, or, under none, the issues filed under none or the AI
  // pre-review; set as the mark is followed, as React adjusts state to a prop
  const [ revealed, setRevealed ] = useState(reveal);
  // With the letter open, a mark followed is pointed out in the letter and beside it instead
  const [ letterPick, setLetterPick ] = useState<{ id: string; at: number }>();
  if (reveal !== revealed) {
    setRevealed(reveal);
    if (reveal && chosen === LETTER
      && letter?.draft.sections.some(section => section.points.some(point => point.id === reveal.id))) {
      setLetterPick(reveal);
    } else if (reveal) {
      const holds = (risk: RiskReview) => [ ...risk.aiOpen, ...risk.issues,
        ...risk.mismatches.map(one => one.evidence) ].some(note => note.id === reveal.id);
      setChosen(risks.find(holds)?.topic.path
        ?? (props.unfiled.some(note => note.id === reveal.id) ? UNFILED : AI_TAB));
    }
  }
  useEffect(() => {
    if (reveal) {
      const id = CSS.escape(reveal.id);
      pulseWhenThere(() => document.querySelector<HTMLElement>(
        `[data-review-panel] [data-note="${id}"], [data-review-panel] [role="row"][data-id="${id}"]`), "center");
    }
  }, [ reveal ]);
  const [ root, setRoot ] = useState<HTMLDivElement | null>(null);
  const compact = useNarrowerThan(root, WIDE);
  const wide = full || !compact;
  const focusing = !full && !compact && props.listFolded;
  useEffect(() => onFocusing?.(focusing), [ focusing, onFocusing ]);
  // Showing where something was raised brings the page back beside the review
  const show = (note: ReviewNote) => {
    onFull(false);
    doing.open(note);
  };
  // The letter is read over the whole workspace, where its page has room, as the entry chosen
  const openLetter = () => {
    setChosen(LETTER);
    onFull(true);
  };

  return (
    <Box ref={setRoot} data-review-panel sx={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>
      {(props.headed ?? true) && <Stack direction="row" sx={{ alignItems: "center", gap: 2, pl: 1.5, pr: 0.5, py: 1,
        ...wide ? { borderBottom: 1, borderColor: "divider" } : {} }}>
        {/* Over the workspace, where the tabs naming it are hidden */}
        {full && !compact && <Typography variant="panelTitle">Review</Typography>}
        <Box sx={{ flex: 1 }} />
        {/* Over the whole workspace, the submission folded beside it, Esc and the browser's Back also take it back */}
        <Tooltip disableInteractive title={full ? "Back beside the page" : "Open over the whole workspace"}
          placement="left">
          <IconButton size="small" onClick={() => onFull(!full)}
            aria-label={full ? "Show the review beside the page" : "Show the review over the whole workspace"}>
            {full ? <CloseFullscreenOutlined fontSize="small" /> : <OpenInFullOutlined fontSize="small" />}
          </IconButton>
        </Tooltip>
      </Stack>}
      {wide ? <WideReview {...props} chosen={chosen} onChosen={setChosen} row={!compact} roomy={full}
        onShow={show} letterPick={letterPick} /> : (
      // An open entry's name stays at the top, which the fade would hide
        <PanelBody fadeTop={chosen === undefined}>
          {/* The entries the whole review lists, unfolding in place */}
          <Box sx={openSx(chosen === AI_TAB)}>
            <PanelEntry open={chosen === AI_TAB} onClick={() => setChosen(chosen === AI_TAB ? undefined : AI_TAB)}
              icon={<AutoAwesomeOutlined sx={{ fontSize: "1rem", color: "assistant.main" }} />}>
              <Typography variant="body2" sx={{ color: "assistant.main", fontWeight: "fontWeightMedium" }}>
                AI pre-review ({openFindingsOf(risks)})
              </Typography>
            </PanelEntry>
            {chosen === AI_TAB && (
              <Box sx={{ px: 1.5, pb: 1.5 }}><AiPreReview {...props} fitRows onShow={show} /></Box>
            )}
          </Box>
          <Box sx={{ borderTop: 1, borderColor: "divider", ...openSx(chosen === ALL_ISSUES) }}>
            <PanelEntry open={chosen === ALL_ISSUES}
              onClick={() => setChosen(chosen === ALL_ISSUES ? undefined : ALL_ISSUES)}
              icon={<RateReviewOutlined sx={{ fontSize: "1rem", color: "issue.main" }} />}>
              <Typography variant="body2" sx={{ color: "issue.main", fontWeight: "fontWeightMedium" }}>
                Your issues ({props.issueCount})
              </Typography>
            </PanelEntry>
            {chosen === ALL_ISSUES && (
              <Box sx={{ px: 1.5, pb: 1.5 }}><AllIssues {...props} fitRows onShow={show} /></Box>
            )}
          </Box>
          {letter && (
            <Box sx={{ borderTop: 1, borderColor: "divider", ...openSx(chosen === LETTER) }}>
              <PanelEntry open={chosen === LETTER} onClick={() => setChosen(chosen === LETTER ? undefined : LETTER)}
                icon={<ForwardToInboxOutlined sx={{ fontSize: "1rem", color: "primary.main" }} />}>
                <LetterName letter={letter} />
              </PanelEntry>
              {chosen === LETTER && <LetterSummary letter={letter} onOpen={openLetter} />}
            </Box>
          )}
          <RisksLabel risks={risks} group={props.group} lettered={Boolean(letter)} dense />
          {risks.map(risk => (
            <Box key={risk.topic.path}
              sx={{ borderTop: 1, borderColor: "divider", ...openSx(chosen === risk.topic.path) }}>
              <RiskHeading
                risk={risk} doing={doing} open={chosen === risk.topic.path}
                onClick={() => setChosen(chosen === risk.topic.path ? undefined : risk.topic.path)}
              />
              {chosen === risk.topic.path && (
              // Under the risk's name: past its caret and its icon's slot, as PanelEntry lays them out
                <Box sx={{ pl: 6.5, pr: 1.5, pb: 1.25 }}>
                  <RiskDetail risk={risk} dense about={props.about} doing={doing} onShow={show} />
                </Box>
              )}
            </Box>
          ))}
          <Box sx={{ borderTop: 1, borderColor: "divider", ...openSx(chosen === UNFILED) }}>
            <PanelEntry open={chosen === UNFILED}
              onClick={() => setChosen(chosen === UNFILED ? undefined : UNFILED)}>
              <OtherName count={props.unfiled.length} counted={chosen !== UNFILED} />
            </PanelEntry>
            {chosen === UNFILED && (
              <Box sx={{ px: 1.5, pb: 1.5 }}><OtherIssues {...props} fitRows onShow={show} /></Box>
            )}
          </Box>
          <Others others={props.others} doing={doing} />
        </PanelBody>
      )}
      <Decision decision={doing.decision} wide={wide} />
    </Box>
  );
}

// The letter asking for changes, drawn from the reviewer's issues, and what can be done with it
export interface LetterWriting {
  draft: DraftedLetter;
  // Shows it as it is sent, to send it
  send: () => void;
  // Why it cannot be sent now, as when the review waits on no decision
  sendBlocked?: string;
}

// The Letter of Review's entry: its name, and how many points it makes
function LetterName({ letter }: { letter: LetterWriting }) {
  const points = letter.draft.sections.flatMap(section => section.points).length;
  return (
    <Box sx={{ flex: 1, minWidth: 0 }}>
      <Typography variant="body2" sx={{ color: "primary.main", fontWeight: "fontWeightMedium" }}>
        Letter of Review
      </Typography>
      <Typography variant="caption" sx={{ display: "block", color: "text.secondary" }}>
        {points === 1 ? "1 point" : `${points} points`}
      </Typography>
    </Box>
  );
}

// Beside the page, where the letter's page has no room: what it says, by its headings, and a way to read it whole
function LetterSummary({ letter, onOpen }: { letter: LetterWriting; onOpen: () => void }) {
  const sections = letter.draft.sections.filter(section => section.points.length > 0);
  return (
    <Stack spacing={1} sx={{ pl: 6.5, pr: 1.5, pb: 1.5 }}>
      {sections.length === 0 ? (
        <Typography variant="body2" sx={{ color: "text.secondary" }}>
          {"No points yet. It's drawn from your open issues."}
        </Typography>
      ) : (
        <Box component="ul" sx={{ m: 0, pl: 2.5, typography: "body2" }}>
          {sections.map(section => (
            <li key={section.heading}>
              {section.heading} ({section.points.length})
            </li>
          ))}
        </Box>
      )}
      <Button size="small" variant="text" startIcon={<OpenInFullOutlined />} onClick={onOpen}
        sx={{ alignSelf: "flex-start", px: 0.75, ml: -0.75 }}>
        Open the letter
      </Button>
    </Stack>
  );
}

// The letter beside the issues it says, in its order, under its headings: an issue changed is the letter changed. A
// point clicked in the letter selects its issue, and an issue clicked its point. Sending it, after making sure,
// asks the study team for changes. Side by side given the room, the issues over the page otherwise
function LetterView(props: ReviewProps & {
  letter: LetterWriting;
  onShow: (note: ReviewNote) => void;
  // Whether it has the whole workspace; beside the page, an issue chosen is also shown there
  roomy?: boolean;
  // A mark on the page followed to an issue in the letter
  pick?: { id: string; at: number };
  // What goes before its title, such as the way to bring the review contents back, and what ends its line
  lead?: ReactNode;
  trail?: ReactNode;
}) {
  const { letter, doing } = props;
  const [ selected, setSelected ] = useState<string>();
  const [ picked, setPicked ] = useState(props.pick);
  if (props.pick !== picked) {
    setPicked(props.pick);
    setSelected(props.pick?.id);
  }
  useEffect(() => {
    if (picked) {
      for (const id of [ `letter-point-${picked.id}`, `review-issue-${picked.id}` ]) {
        document.getElementById(id)?.scrollIntoView({ block: "nearest" });
      }
    }
  }, [ picked ]);
  const [ root, setRoot ] = useState<HTMLDivElement | null>(null);
  const row = !useNarrowerThan(root, 960);
  const issues = new Map([ ...props.risks.flatMap(one => one.issues), ...props.unfiled ]
    .map(issue => [ issue.id, issue ]));
  // An issue chosen, in the list or in the letter, is shown in the other, and, beside the page, on the page too
  const select = (id: string, show: string) => {
    setSelected(id);
    document.getElementById(show)?.scrollIntoView({ block: "nearest", behavior: "smooth" });
    const issue = issues.get(id);
    if (!props.roomy && issue && anchored(issue)) {
      props.onShow(issue);
    }
  };
  const points = letter.draft.sections.flatMap(section => section.points).length;
  const send = (
    <Button variant="contained" size="small" startIcon={<ForwardToInboxOutlined />}
      disabled={Boolean(letter.sendBlocked)} onClick={letter.send}>
      Send
    </Button>
  );
  return (
    // Side by side, the issues and the letter scroll each on their own, filling the pane
    <Stack ref={setRoot} spacing={2} sx={row ? { height: "100%", minHeight: 0 } : undefined}>
      <Stack direction="row" sx={{ alignItems: "center", gap: 2, flexWrap: "wrap" }}>
        <Stack direction="row" sx={{ alignItems: "center", gap: 1, flex: 1 }}>
          {props.lead}
          <Typography variant="h6" component="h3">Letter of Review</Typography>
        </Stack>
        {letter.sendBlocked
          ? <Tooltip disableInteractive title={letter.sendBlocked}><span>{send}</span></Tooltip>
          : send}
        {props.trail}
      </Stack>
      <Typography variant="body2" sx={{ color: "text.secondary" }}>
        Drawn from your open issues; to change what it says, change your issues here. Sending it asks the study team
        for changes, and keeps your issues as they read them.
      </Typography>
      <Stack direction={row ? "row" : "column"}
        sx={{ gap: 3, alignItems: row ? "stretch" : "flex-start", ...row ? { flex: 1, minHeight: 0 } : {} }}>
        <Box sx={{ flex: row ? "2 1 0" : undefined, minWidth: row ? 300 : 0, maxWidth: row ? 560 : undefined,
          width: row ? undefined : "100%", ...row ? { overflowY: "auto", pr: 1 } : {} }}>
          {points === 0 && (
            <Typography variant="body2" sx={{ color: "text.secondary" }}>
              {"You haven't raised any issues for the study team, so the letter has no points."}
            </Typography>
          )}
          {letter.draft.sections.map(section => (
            <Box key={section.heading} sx={{ mb: 2 }}>
              <Typography variant="subtitle2" component="h4" sx={{ fontWeight: "fontWeightBold", mb: 0.5 }}>
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
                      <IssueRow issue={issue} dense={false} replyable={false} about={props.about} doing={doing}
                        onShow={props.onShow} />
                    </Box>
                  </Stack>
                );
              })}
            </Box>
          ))}
        </Box>
        <Box sx={{ flex: row ? "3 1 0" : undefined, minWidth: 0, maxWidth: row ? 880 : undefined,
          width: row ? undefined : "100%", ...row ? { overflowY: "auto", pb: 1 } : {} }}>
          <LetterPage letter={letter.draft} selected={selected}
            onSelect={id => select(id, `review-issue-${id}`)} />
        </Box>
      </Stack>
    </Stack>
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
  letter?: LetterWriting;
  letterPick?: { id: string; at: number };
  listFolded: boolean;
  onListFolded: (folded: boolean) => void;
  onFull: (full: boolean) => void;
}) {
  const { risks, doing, row, onShow, letter } = props;
  // PRETOTYPE: laid out side by side, the contents can be folded away, to give its page the room, as for the letter
  // beside a document
  const folded = row && props.listFolded;
  const setListFolded = props.onListFolded;
  const [ sideElement, setSideElement ] = useState<HTMLDivElement | null>(null);
  const sideEdges = useScrollEdges(sideElement);
  // Whether the AI pre-review found anything: it may have failed, or the submission left it nothing to say
  const aiFound = risks.some(one => Boolean(one.assessment) || one.aiOpen.length > 0 || one.mismatches.length > 0
    || one.aiSettled.length > 0);
  // Until the reviewer has chosen a page: what the AI found while they have raised nothing yet, to triage first; their
  // issues once they have, or when the AI found nothing
  const chosen = props.chosen ?? (aiFound && props.issueCount === 0 ? AI_TAB : ALL_ISSUES);
  const setChosen = props.onChosen;
  const openFindings = openFindingsOf(risks);
  const risk = risks.find(one => one.topic.path === chosen);
  const entrySx = (page: string) => ({ display: "flex", justifyContent: "flex-start", gap: 0.75, width: "100%",
    textAlign: "start", px: 2, py: 1, borderTop: 1, borderColor: "divider",
    bgcolor: chosen === page ? "background.paper" : undefined }) as const;
  // The entry chosen, and, when it has scrolled out of the list, the edge it left by, where a copy of it stays pinned,
  // as the contents keep the part being read
  const [ chosenElement, setChosenElement ] = useState<HTMLElement | null>(null);
  const [ pinned, setPinned ] = useState<"above" | "below">();
  useEffect(() => {
    if (!chosenElement || !sideElement) {
      return undefined;
    }
    const observer = new IntersectionObserver(([ seen ]) => setPinned(seen.isIntersecting ? undefined
      : seen.boundingClientRect.top < (seen.rootBounds?.top ?? 0) ? "above" : "below"), { root: sideElement });
    observer.observe(chosenElement);
    return () => observer.disconnect();
  }, [ chosenElement, sideElement ]);
  const chosenRef = (page: string) => (chosen === page ? setChosenElement : undefined);
  // What the pinned copy says: the entry's mark and name, without what it counts
  const chosenName = chosen === LETTER && letter ? (
    <>
      <ForwardToInboxOutlined sx={{ fontSize: "1rem", color: "primary.main" }} />
      <LetterName letter={letter} />
    </>
  ) : chosen === AI_TAB ? (
    <>
      <AutoAwesomeOutlined sx={{ fontSize: "1rem", color: "assistant.main" }} />
      <Typography variant="body2" sx={{ color: "assistant.main", fontWeight: "fontWeightMedium" }}>
        AI pre-review ({openFindings})
      </Typography>
    </>
  ) : chosen === ALL_ISSUES ? (
    <>
      <RateReviewOutlined sx={{ fontSize: "1rem", color: "issue.main" }} />
      <Typography variant="body2" sx={{ color: "issue.main", fontWeight: "fontWeightMedium" }}>
        Your issues ({props.issueCount})
      </Typography>
    </>
  ) : (
    <>
      <Box sx={{ width: "1rem", flexShrink: 0, color: "text.secondary" }}>
        {risk?.considered && <CheckOutlined titleAccess="Considered" sx={{ fontSize: "1rem", display: "block" }} />}
      </Box>
      <Typography variant="body2" sx={{ color: risk?.considered ? "text.secondary" : "primary.main",
        fontWeight: "fontWeightMedium" }}>
        {risk?.topic.label ?? "Other"}
      </Typography>
    </>
  );
  const pinnedEntry = pinned && (
    <ButtonBase
      aria-label="Scroll the list to the entry shown" onClick={() => chosenElement?.scrollIntoView({ block: "nearest",
        behavior: "smooth" })}
      sx={theme => ({ ...entrySx(chosen), borderTop: "none", alignItems: "center", position: "relative", zIndex: 1,
        boxShadow: theme.shadows[3] })}
    >
      {chosenName}
      <Box sx={{ flex: 1 }} />
      {pinned === "above"
        ? <ArrowUpwardOutlined fontSize="small" sx={{ color: "text.secondary" }} />
        : <ArrowDownwardOutlined fontSize="small" sx={{ color: "text.secondary" }} />}
    </ButtonBase>
  );
  // Folded, each page's title says where it is in the review, which leads back to the contents; beside the page, where
  // the tabs go, the way over the whole workspace ends the title's line
  const crumb = folded && (
    <>
      <Link component="button" variant="h6" underline="always" onClick={() => setListFolded(false)}
        sx={{ fontWeight: "fontWeightRegular" }}>
        Review
      </Link>
      <ChevronRightOutlined sx={{ color: "text.secondary" }} />
    </>
  );
  const trail = folded && !props.roomy && (
    <Tooltip disableInteractive title="Open over the whole workspace" placement="left">
      <IconButton size="small" aria-label="Show the review over the whole workspace" onClick={() => props.onFull(true)}>
        <OpenInFullOutlined fontSize="small" />
      </IconButton>
    </Tooltip>
  );
  return (
    <Stack direction={row ? "row" : "column"} sx={{ flex: 1, minHeight: 0 }}>
      {!folded && (
        <Stack sx={{ width: row ? (props.roomy ? 340 : 280) : undefined, flexShrink: 0, minHeight: 0,
          maxHeight: row ? undefined : 240, position: "relative" }}>
          {row && (
            <FoldButton side="left" label="Fold the review contents away" place={{ right: -FOLD_SIZE / 2 }}
              onClick={() => setListFolded(true)} />
          )}
          {pinned === "above" && pinnedEntry}
          {/* No scrollbar between the list and the page beside it, as in the contents panel: the fade says there is more */}
          <Box ref={setSideElement} sx={{ flex: 1, minHeight: 0, overflowY: "auto", scrollbarWidth: "none",
            maskImage: fadeEdges(sideEdges) }}>
            <ButtonBase ref={chosenRef(AI_TAB)} onClick={() => setChosen(AI_TAB)}
              sx={{ ...entrySx(AI_TAB), borderTop: "none" }}>
              <AutoAwesomeOutlined sx={{ fontSize: "1rem", color: "assistant.main" }} />
              <Typography variant="body2" sx={{ color: "assistant.main", fontWeight: "fontWeightMedium" }}>
                AI pre-review ({openFindings})
              </Typography>
            </ButtonBase>
            <ButtonBase ref={chosenRef(ALL_ISSUES)} onClick={() => setChosen(ALL_ISSUES)} sx={entrySx(ALL_ISSUES)}>
              <RateReviewOutlined sx={{ fontSize: "1rem", color: "issue.main" }} />
              <Typography variant="body2" sx={{ color: "issue.main", fontWeight: "fontWeightMedium" }}>
                Your issues ({props.issueCount})
              </Typography>
            </ButtonBase>
            {letter && (
              <ButtonBase ref={chosenRef(LETTER)} onClick={() => setChosen(LETTER)} sx={entrySx(LETTER)}>
                <ForwardToInboxOutlined sx={{ fontSize: "1rem", color: "primary.main" }} />
                <LetterName letter={letter} />
              </ButtonBase>
            )}
            <RisksLabel risks={risks} group={props.group} lettered={Boolean(letter)} />
            {risks.map(one => (
              <Box key={one.topic.path} ref={chosenRef(one.topic.path)} sx={{ borderTop: 1, borderColor: "divider",
                bgcolor: chosen === one.topic.path ? "background.paper" : undefined }}>
                {/* Lined up with the entries over them, a tick where they have an icon, once the risk is considered */}
                <ButtonBase onClick={() => setChosen(one.topic.path)} sx={{ ...entrySx(one.topic.path), borderTop: "none",
                  alignItems: "flex-start", bgcolor: undefined }}>
                  <Box sx={{ width: "1rem", flexShrink: 0, color: "text.secondary", mt: 0.25 }}>
                    {one.considered && <CheckOutlined titleAccess="Considered" sx={{ fontSize: "1rem", display: "block" }} />}
                  </Box>
                  <RiskName risk={one} />
                </ButtonBase>
              </Box>
            ))}
            <ButtonBase ref={chosenRef(UNFILED)} onClick={() => setChosen(UNFILED)} sx={entrySx(UNFILED)}>
              <Box sx={{ width: "1rem", flexShrink: 0 }} />
              <OtherName count={props.unfiled.length} />
            </ButtonBase>
            <Others others={props.others} doing={doing} />
          </Box>
          {pinned === "below" && pinnedEntry}
        </Stack>
      )}
      <Box sx={{ flex: 1, minWidth: 0, overflowY: "auto", px: { xs: 2, md: 3 }, py: 2.5, containerType: "inline-size",
        bgcolor: "background.paper" }}>
        {risk && (
          <Stack spacing={2}>
            <Stack direction="row" sx={{ alignItems: "center", gap: 1 }}>
              {crumb}
              <Typography variant="h6" component="h3" sx={{ flex: 1 }}>{risk.topic.label}</Typography>
              <ConsideredCheck labelled risk={risk} doing={doing} />
              {trail}
            </Stack>
            <RiskDetail risk={risk} about={props.about} doing={doing} onShow={onShow} />
          </Stack>
        )}
        {chosen === AI_TAB && (
          <AiPreReview {...props} onShow={onShow} trailing={trail} heading={<>
            {crumb}
            <AutoAwesomeOutlined sx={{ color: "assistant.main" }} />
            <Typography variant="h6" component="h3" sx={{ flex: 1, color: "assistant.main" }}>AI pre-review</Typography>
          </>} />
        )}
        {chosen === ALL_ISSUES && (
          <AllIssues {...props} onShow={onShow} trailing={trail} heading={<>
            {crumb}
            <RateReviewOutlined sx={{ color: "issue.main" }} />
            <Typography variant="h6" component="h3" sx={{ flex: 1, color: "issue.main" }}>Your issues</Typography>
          </>} />
        )}
        {chosen === LETTER && letter && (
          <LetterView {...props} letter={letter} onShow={onShow} pick={props.letterPick} lead={crumb} trail={trail} />
        )}
        {chosen === UNFILED && (
          // As a risk's page is: its name over its issues
          <Stack spacing={2}>
            <Stack direction="row" sx={{ alignItems: "center", gap: 1 }}>
              {crumb}
              <Typography variant="h6" component="h3" sx={{ flex: 1 }}>Other</Typography>
              {trail}
            </Stack>
            <OtherIssues {...props} onShow={onShow} />
          </Stack>
        )}
      </Box>
    </Stack>
  );
}

const UNFILED = "unfiled";
const LETTER = "letter";
const isEntry = (value: unknown): value is string | null => typeof value === "string" || value === null;
const ALL_ISSUES = "all";
const AI_TAB = "ai";

// Before the risks, what they are, how many are ticked off as considered, and what ticking one off means
function RisksLabel({ risks, group, lettered = false, dense = false }: {
  risks: RiskReview[];
  group?: string;
  // Whether the reviewer's issues go into a Letter of Review
  lettered?: boolean;
  dense?: boolean;
}) {
  const considered = risks.filter(risk => risk.considered).length;
  return (
    <Box sx={{ borderTop: 1, borderColor: "divider", px: dense ? 1.5 : 2, pt: 1.5, pb: 1 }}>
      <Stack direction="row" sx={{ alignItems: "center", gap: 0.5 }}>
        <Typography variant="body2" component="h3" sx={{ fontWeight: "fontWeightMedium" }}>
          {group ? `${group} risks to consider` : "Your risks to consider"}
        </Typography>
        <Tooltip disableInteractive placement="right" title={"Your reviewer group's list of what to check in this "
          + `submission. Issues you file under a risk ${lettered ? "go into your Letter of Review"
            : "go to the study team when you request changes"}, and approving reminds you of any risk not yet ticked.`}>
          <InfoOutlined aria-label="About the risks" sx={{ fontSize: "1rem", color: "text.secondary" }} />
        </Tooltip>
        <Typography variant="caption" sx={{ ml: "auto", color: "text.secondary", whiteSpace: "nowrap" }}>
          {considered} of {risks.length} considered
        </Typography>
      </Stack>
      <LinearProgress
        variant="determinate" value={risks.length ? considered / risks.length * 100 : 0}
        aria-label="Risks considered" sx={{ my: 0.75, borderRadius: 1 }}
      />
      <Typography variant="caption" component="p" sx={{ color: "text.secondary" }}>
        Tick each risk once you&apos;ve considered it, whether or not you raise an issue.
      </Typography>
    </Box>
  );
}

// What the AI found that is still open: its concerns, and its mismatches, an answer two risks draw on counted once
function openFindingsOf(risks: RiskReview[]): number {
  return risks.reduce((sum, one) => sum + one.aiOpen.length, 0)
    + new Set(risks.flatMap(one => one.mismatches.map(mismatch => mismatch.evidence.id))).size;
}

// Everything the AI pre-review found, in a table: filling the height beside the list, or, unfolded in the list, as
// tall as its rows
function AiPreReview({ risks, about, doing, onShow, heading, trailing, fitRows = false }: ReviewProps & {
  // What ends the heading's line
  trailing?: ReactNode;
  onShow: (note: ReviewNote) => void;
  heading?: ReactNode;
  fitRows?: boolean;
}) {
  const [ grouped, setGrouped ] = useState(false);
  const [ withSettled, setWithSettled ] = useState(false);
  const grid = (
    <AiGrid
      risks={risks} grouped={grouped} settled={withSettled} fitRows={fitRows} about={about} onShow={onShow}
      onMakeIssue={concern => doing.makeIssue(concern,
        concern.suggestion ? `${concern.text}\n\n${concern.suggestion}` : concern.text,
        concern.severity, concern.topicPath)}
      onDismiss={concern => doing.dismiss(concern)} onUndismiss={concern => doing.undismiss(concern)}
    />
  );
  return (
    <Stack spacing={fitRows ? 1 : 2} sx={fitRows ? undefined : { height: "100%", minHeight: 360 }}>
      <Stack direction="row" sx={{ alignItems: "center", columnGap: 2, flexWrap: "wrap" }}>
        {heading}
        <FormControlLabel label="Group by risk"
          control={<Switch size={fitRows ? "small" : "medium"} checked={grouped}
            onChange={event => setGrouped(event.target.checked)} />} />
        <FormControlLabel label="Show settled"
          control={<Switch size={fitRows ? "small" : "medium"} checked={withSettled}
            onChange={event => setWithSettled(event.target.checked)} />} />
        {trailing}
      </Stack>
      {fitRows ? grid : <Box sx={{ flex: 1, minHeight: 0 }}>{grid}</Box>}
    </Stack>
  );
}

// All the reviewer's issues, in a table: filling the height beside the list, or, unfolded in the list, as tall as its
// rows
function AllIssues({ risks, unfiled, about, doing, onShow, heading, trailing, fitRows = false }: ReviewProps & {
  // What ends the heading's line
  trailing?: ReactNode;
  onShow: (note: ReviewNote) => void;
  heading?: ReactNode;
  fitRows?: boolean;
}) {
  return (
    <IssueTable
      fill={!fitRows} fitRows={fitRows} dense={fitRows}
      issues={[ ...risks.flatMap(one => one.issues), ...unfiled ]}
      risks={risks.map(one => ({ path: one.topic.path, label: one.topic.label }))}
      about={about} doing={doing} onShow={onShow} heading={heading ?? <Box sx={{ flex: 1 }} />} trailing={trailing}
    />
  );
}

// Whether the reviewer has considered a risk, ticked off as done: named "Considered" beside the risk's title, or, in a
// list, named by a tooltip
function ConsideredCheck({ risk, doing, labelled = false }: {
  risk: RiskReview;
  doing: ReviewDoing;
  labelled?: boolean;
}) {
  const [ saving, setSaving ] = useState(false);
  const check = (
    <Checkbox
      size="small" checked={risk.considered} disabled={saving}
      onChange={(_event, checked) => {
        setSaving(true);
        void doing.consider(risk.topic.path, checked).finally(() => setSaving(false));
      }}
      slotProps={{ input: { "aria-label": `${risk.topic.label} considered` } }}
    />
  );
  return labelled ? (
    <FormControlLabel control={check} label="Considered" labelPlacement="end"
      sx={{ mr: 0, "& .MuiFormControlLabel-label": { typography: "body2", color: "text.secondary" } }} />
  ) : (
    <Tooltip disableInteractive title={risk.considered ? "Considered. Click to unmark" : "Mark as considered"}
      placement="left">
      {check}
    </Tooltip>
  );
}

// A risk's name, grey once considered, and what is under it
function RiskName({ risk, counted = true }: { risk: RiskReview; counted?: boolean }) {
  return <EntryName label={risk.topic.label} done={risk.considered} counts={counted ? countsOf(risk) : []} />;
}

// An entry's name, grey once done with, and what is under it, counted as the contents count what is in a part
function EntryName({ label, done = false, counts }: {
  label: string;
  done?: boolean;
  counts: ReturnType<typeof countsOf>;
}) {
  return (
    <Box sx={{ flex: 1, minWidth: 0 }}>
      <Typography variant="body2"
        sx={{ color: done ? "text.secondary" : "primary.main", fontWeight: "fontWeightMedium" }}>
        {label}
      </Typography>
      {counts.length > 0 && (
        <Tooltip disableInteractive placement="right" title={counts.map(({ kind, count }) =>
          plural(count, COUNTED[kind][0], COUNTED[kind][1])).join(", ")}>
          <Box component="span" sx={{ display: "flex", flexWrap: "wrap", gap: 0.5, mt: 0.5 }}>
            {counts.map(({ kind, count }) => <KindCount key={kind} kind={kind} count={count} />)}
          </Box>
        </Tooltip>
      )}
    </Box>
  );
}

// An entry of the panel's list unfolded, white with what is under it, as the whole review's chosen entry and its page
const openSx = (open: boolean) => (open ? { bgcolor: "background.paper" } : {});

// An entry in the panel's list, unfolding under it: the caret saying whether it is open, a slot for its icon, so
// every entry's name lines up as in the whole review's list, its name, and what can be done with it at the end, apart,
// so opening it and doing that are not mistaken for each other
function PanelEntry({ open, onClick, icon, end, children }: {
  open: boolean;
  onClick: () => void;
  icon?: ReactNode;
  end?: ReactNode;
  children: ReactNode;
}) {
  return (
    // One row under the pointer, though it opens and its end acts apart. Open, it stays at the top while what is
    // under it scrolls by, so the reviewer keeps sight of where they are: white, with a line under it, and under the
    // pointer a grey shade laid over the white, so nothing shows through
    <Stack direction="row" sx={theme => {
      const shade = (theme.vars ?? theme).palette.action.hover;
      return { alignItems: "flex-start",
        ...open ? { position: "sticky", top: 0, zIndex: 3, bgcolor: "background.paper",
          boxShadow: `0 1px 0 ${(theme.vars ?? theme).palette.divider}`,
          "&:hover": { backgroundImage: `linear-gradient(${shade}, ${shade})` } }
          : { "&:hover": { bgcolor: theme.alpha(theme.palette.background.paper, 0.5) } } };
    }}>
      <ButtonBase
        onClick={onClick} aria-expanded={open}
        sx={{ flex: 1, minWidth: 0, justifyContent: "flex-start", alignItems: "flex-start", gap: 0.75,
          textAlign: "start", py: 0.75, pl: 1, pr: 0.5, minHeight: 40 }}
      >
        <Stack direction="row" sx={{ alignItems: "center", flexShrink: 0 }}>
          {open ? <ExpandMoreOutlined fontSize="small" /> : <ChevronRightOutlined fontSize="small" />}
          <Box sx={{ width: "1rem", display: "flex", ml: 0.25 }}>{icon}</Box>
        </Stack>
        {children}
      </ButtonBase>
      {end && <Box sx={{ mr: 0.75, mt: 0.25 }}>{end}</Box>}
    </Stack>
  );
}

// A risk in the panel's list: its name and what is under it, ticked off as considered at the end
function RiskHeading({ risk, doing, open, onClick }: {
  risk: RiskReview;
  doing: ReviewDoing;
  open: boolean;
  onClick: () => void;
}) {
  return (
    <PanelEntry open={open} onClick={onClick} end={<ConsideredCheck risk={risk} doing={doing} />}>
      {/* Open, what it counts is all under it */}
      <RiskName risk={risk} counted={!open} />
    </PanelEntry>
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
        <Section title="AI-found mismatches with the protocol" dense={dense}>
          {risk.mismatches.map(mismatch => (
            <Stack key={mismatch.evidence.id} data-note={mismatch.evidence.id} direction="row"
              sx={{ gap: 0.75, alignItems: "flex-start" }}>
              <NoteKindIcon kind="contradiction" sx={{ fontSize: "1rem", color: "warning.main", mt: 0.25 }} />
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
  // The reviewer's own, in the grid of all their issues, narrowed to the risk
  const yours = (
    <IssueTable
      compact dense={dense} issues={risk.issues} risks={[ { path: risk.topic.path, label: risk.topic.label } ]}
      defaultRisk={risk.topic.path} about={about} doing={doing} onShow={onShow}
      heading={<IssuesTitle count={risk.issues.length} />}
    />
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
  // the whole width, what they write
  return (
    <Stack spacing={3}>
      <Box sx={{ display: "grid", gridTemplateColumns: "minmax(0, 1fr)", gap: 3, alignItems: "start",
        "@container (min-width: 900px)": { gridTemplateColumns: "minmax(0, 5fr) minmax(0, 7fr)", columnGap: 4 } }}>
        {guidance ?? <Box />}
        {ai || <Box />}
      </Box>
      {yours}
    </Stack>
  );
}

// The reviewer's issues as a table under a heading, with a way to add one in a row of its own at the table's top;
// filling the height it is given, or as tall as its rows
function IssueTable({ heading, trailing, note, issues, risks, defaultRisk, compact = false, keepRisk = false,
  fill = false, fitRows = compact, dense = false, about, doing, onShow }: {
  heading: ReactNode;
  // What ends the heading's line
  trailing?: ReactNode;
  note?: string;
  issues: ReviewNote[];
  risks: { path: string; label: string }[];
  defaultRisk?: string;
  compact?: boolean;
  keepRisk?: boolean;
  fill?: boolean;
  fitRows?: boolean;
  // In the panel beside the page: a quiet way to add one, and nothing said when there are none
  dense?: boolean;
  about: (note: ReviewNote) => string;
  doing: ReviewDoing;
  onShow: (note: ReviewNote) => void;
}) {
  const [ adding, setAdding ] = useState(false);
  const grid = (
    <IssuesGrid
      compact={compact} keepRisk={keepRisk} fitRows={fitRows} sayEmpty={!dense} issues={issues} risks={risks}
      about={about} onShow={onShow}
      onRemove={issue => doing.removeIssue(issue)} onReply={(issue, text) => doing.reply(issue, text)}
      onEditReply={(reply, text) => doing.editReply(reply, text)} onRemoveReply={reply => doing.removeReply(reply)}
      onChange={(issue, text, severity, topic, nature) => doing.editIssue(issue, text, severity, topic, nature)}
      adding={adding} defaultRisk={defaultRisk} defaultNature={doing.defaultNature}
      onAddingDone={() => setAdding(false)}
      onAdd={(text, severity, topic, nature) => doing.addIssue(topic || undefined, text, severity, nature)}
    />
  );
  return (
    <Stack spacing={fill ? 2 : 1} sx={fill ? { height: "100%", minHeight: 360 } : undefined}>
      <Stack direction="row" sx={{ alignItems: "center", gap: 2, flexWrap: "wrap" }}>
        {heading}
        <Button size="small" variant={dense ? "text" : "contained"} startIcon={<AddOutlined />} disabled={adding}
          onClick={() => setAdding(true)}>
          Add an issue
        </Button>
        {trailing}
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
  // In the panel, its first three lines, fading out where there is more
  const [ shown, setShown ] = useState<HTMLElement | null>(null);
  const edges = useScrollEdges(shown);
  const clamped = dense && !open;
  const more = rest.length > 0 || (clamped && edges.below);
  const toggle = more || open ? () => setOpen(!open) : undefined;
  const variant = dense ? "caption" : "body2";
  return (
    // A click anywhere on it opens it or closes it, unless it selects some of its text
    <Box
      onClick={toggle && (() => {
        if (!window.getSelection()?.toString()) {
          toggle();
        }
      })}
      sx={{ bgcolor: "background.muted", borderRadius: 1, px: dense ? 1 : 2, py: dense ? 0.75 : 1.5,
        cursor: toggle ? "pointer" : undefined }}
    >
      <Stack direction="row" sx={{ alignItems: "center", gap: 0.75, mb: 0.5 }}>
        <MenuBookOutlined sx={{ fontSize: "1rem", color: "text.secondary" }} />
        <Typography variant="caption" component="h4" sx={{ fontWeight: "fontWeightBold", color: "text.secondary" }}>
          What to look at
        </Typography>
      </Stack>
      <Typography ref={setShown} variant={variant} component="div"
        sx={theme => ({ whiteSpace: "pre-wrap", color: "text.secondary",
          ...clamped && { overflow: "hidden", maskImage: fadeEdges(edges),
            maxHeight: `calc(3em * ${String(theme.typography[variant].lineHeight)})` } })}>
        {open ? text : first}
      </Typography>
      {toggle && (
        <Link component="button" variant="caption" sx={{ mt: 0.5 }} onClick={event => {
          event.stopPropagation();
          toggle();
        }}>
          {open ? "Less" : "More"}
        </Link>
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
        <AutoAwesomeOutlined sx={{ fontSize: "1.125rem", color: "assistant.main" }} />
        <Typography variant="subtitle2" component="h4" sx={{ fontWeight: "fontWeightBold", color: "assistant.main" }}>
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
      {severityShown(note) && <TagChip tags={[ severityShown(note) ?? "" ]} category="severity" />}
      {note.nature === "suggestion" && <TagChip tags={[ "suggestion" ]} category="issue-kind" />}
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
    <Box data-note={note.id} sx={{ py: 1 }}>
      <RowHead note={note} about={about} />
      {adopting ? (
        <Box sx={{ mt: 0.5 }}>
          <Composer
            kinds={[ "issue" ]} initialText={note.suggestion ? `${note.text}\n\n${note.suggestion}` : note.text}
            initialSeverity={note.severity} initialTopic={note.topicPath ?? topic} saveLabel="Add issue"
            initialNature={note.nature === "suggestion" ? "suggestion" : undefined}
            onCancel={() => setAdopting(false)}
            onSave={async (_kind, text, severity, chosen, nature) => {
              await doing.makeIssue(note, text, severity, chosen, nature);
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
function IssueRow({ issue, dense, replyable = true, about, doing, onShow }: {
  issue: ReviewNote;
  dense: boolean;
  // Whether it offers a reply; not beside the letter, which is drawn from what the issues say
  replyable?: boolean;
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
          kinds={[ "issue" ]} initialText={issue.text} initialSeverity={issue.severity} initialNature={issue.nature}
          initialTopic={issue.topicPath ?? ""} saveLabel="Save"
          onCancel={() => setEditing(false)}
          onSave={async (_kind, text, severity, topic, nature) => {
            await doing.editIssue(issue, text, severity, topic, nature);
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
        {!dense && replyable && !replying && (
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
        {severityShown(note) && <TagChip tags={[ severityShown(note) ?? "" ]} category="severity" />}
        {note.nature === "suggestion" && <TagChip tags={[ "suggestion" ]} category="issue-kind" />}
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

// The reviewer's issues under an entry, titled as the AI's block over them is: its mark and its name, in the issues'
// colour
function IssuesTitle({ count }: { count: number }) {
  return (
    <Stack direction="row" sx={{ alignItems: "center", gap: 0.75, flex: 1, color: "issue.main" }}>
      <RateReviewOutlined sx={{ fontSize: "1.125rem" }} />
      <Typography variant="subtitle2" component="h4" sx={{ fontWeight: "fontWeightBold" }}>
        Your issues ({count})
      </Typography>
    </Stack>
  );
}

// The entry for the reviewer's issues filed under none of their risks, counted as a risk's are
function OtherName({ count, counted = true }: { count: number; counted?: boolean }) {
  return <EntryName label="Other" counts={counted && count > 0 ? [ { kind: "issue", count } ] : []} />;
}

// The reviewer's issues filed under none of their risks: the grid of all their issues, narrowed to these, its risk
// kept to file them
function OtherIssues({ risks, unfiled, about, doing, onShow, fitRows }: ReviewProps & {
  onShow: (note: ReviewNote) => void;
  fitRows?: boolean;
}) {
  return (
    <IssueTable
      compact keepRisk fitRows={fitRows} dense={fitRows} issues={unfiled}
      risks={risks.map(one => ({ path: one.topic.path, label: one.topic.label }))}
      about={about} doing={doing} onShow={onShow} heading={<IssuesTitle count={unfiled.length} />}
    />
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

// The decision, with what was asked of the study team when it is back from them
function Decision({ decision, wide = false }: {
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
  // PRETOTYPE: none of the decisions while one cannot be taken from here, saying why
  const held = (button: ReactNode) => decision.blocked
    ? <Tooltip disableInteractive title={decision.blocked}><span>{button}</span></Tooltip> : button;
  const board = decision.board && held(
    <Button variant="text" size="small" fullWidth={!wide} startIcon={<GroupsOutlined />}
      disabled={Boolean(decision.blocked)} onClick={decision.board}>
      Send to the board
    </Button>,
  );
  return (
    <Stack direction={wide ? { xs: "column", sm: "row" } : "column"} spacing={1}
      sx={{ p: 1.5, borderTop: 1, borderColor: "divider", alignItems: wide ? { sm: "center" } : undefined }}>
      {decision.decided ? <Decided decision={decision} wide={wide} /> : wide && <Box sx={{ flex: 1 }} />}
      {wide && board}
      <Tooltip disableInteractive title={decision.blocked ?? decision.sendBlocked ?? ""}>
        <span>
          <Button variant="outlined" size="small" fullWidth={!wide} startIcon={<ForwardToInboxOutlined />}
            disabled={Boolean(decision.blocked ?? decision.sendBlocked)} onClick={decision.send}>
            {decision.sendLabel}
          </Button>
        </span>
      </Tooltip>
      <Tooltip disableInteractive title={decision.blocked ?? decision.approveBlocked ?? ""}>
        <span>
          <Button variant="contained" size="small" fullWidth={!wide} startIcon={<TaskAltOutlined />}
            disabled={Boolean(decision.blocked ?? decision.approveBlocked)} onClick={decision.approve}>
            {decision.approveLabel}
          </Button>
        </span>
      </Tooltip>
      {!wide && board}
    </Stack>
  );
}
