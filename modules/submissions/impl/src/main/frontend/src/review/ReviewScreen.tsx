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

import { Fragment, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";

import ArrowDownwardOutlined from "@mui/icons-material/ArrowDownwardOutlined";
import ArrowUpwardOutlined from "@mui/icons-material/ArrowUpwardOutlined";
import ChevronLeftOutlined from "@mui/icons-material/ChevronLeftOutlined";
import ChevronRightOutlined from "@mui/icons-material/ChevronRightOutlined";
import CloseOutlined from "@mui/icons-material/CloseOutlined";
import DifferenceOutlined from "@mui/icons-material/DifferenceOutlined";
import HistoryOutlined from "@mui/icons-material/HistoryOutlined";
import OpenInFullOutlined from "@mui/icons-material/OpenInFullOutlined";
import SearchOutlined from "@mui/icons-material/SearchOutlined";
import SplitscreenOutlined from "@mui/icons-material/SplitscreenOutlined";
import {
  Alert,
  Box,
  Button,
  ButtonBase,
  DialogContentText,
  FormControlLabel,
  IconButton,
  Link,
  List,
  ListItemButton,
  ListItemText,
  MenuItem,
  Paper,
  Stack,
  Switch,
  Tab,
  Tabs,
  Tooltip,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
  useMediaQuery,
  useTheme,
  type Theme,
} from "@mui/material";

import type { MarkKind } from "@iap/documents-pdf-viewer/marksModel";
import { getPassageCardStyle } from "@iap/documents-pdf-viewer/passageStyles";
import type { DocumentSearch } from "@iap/documents-pdf-viewer/pdfViewerModel";
import ConfirmActionDialog from "@iap/frontend-commons/components/ConfirmActionDialog";
import LoadError from "@iap/frontend-commons/components/LoadError";
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import SplitHandle from "@iap/frontend-commons/components/SplitHandle";
import { usePublishedPageContext } from "@iap/frontend-commons/pageContext";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { useStoredSetting } from "@iap/frontend-commons/useStoredSetting";
import TagChip from "@iap/tags/TagChip";

import {
  type FormItem,
  type FormQuestion,
  type Requirement,
  type SubmissionForm,
  isDocumentRequirement,
  isApprovalRequirement,
  isFormRequirement,
  isQuestion,
} from "../submissionForm";
import { ReviewerTopics, WRITTEN } from "./Composer";
import Countdown from "./Countdown";
import DocumentChangeCount from "./DocumentChangeCount";
import DocumentReview from "./DocumentReview";
import EmailPreview from "./EmailPreview";
import EscalateDialog from "./EscalateDialog";
import FormReview, { answerText, GroupChips, type ReviewActions } from "./FormReview";
import { SentLetter } from "./LetterDialog";
import LetterPage from "./LetterPage";
import NoteKindIcon, { KindCount } from "./noteIcons";
import { emailText, issuesEmail } from "./notifyEmail";
import { GroupLabels, usePersonName } from "./people";
import { pulseWhenThere } from "./pulse";
import { approvalLetter, draftLetter, letterText } from "./rebLetter";
import { writeComment } from "./reviewApi";
import { answersIn, changedAnswers, comparisonFor, groupShortName, isMine, kindInfo, NOTE_KINDS, notesAbout,
  defaultNature, parseGroups, parseReview, parseTopics, raiseAsIssue, resubmittedAt, sentBackCopy, shortDate,
  withAnswerChanges, withNatures,
  withDone, withSent, keptVersions, asOfVersion,
  type NoteKind, type ReviewData, type ReviewedDocument, type ReviewGroup, type ReviewNote, type Topic } from "./reviewData";
import ReviewPanel, { type Mismatch, type OthersRaised, type ReviewDoing, type RiskReview } from "./ReviewPanel";
import RowSplit from "./RowSplit";
import { SearchContext } from "./SearchHighlight";
import { documentText, formMatches, pageCounts } from "./searchSubmission";
import { FOLD_SIZE, FOLDED_WIDTH, FoldButton, PANEL_HEIGHT, PanelBody } from "./sidePanel";
import { withSources } from "./sources";
import SubmissionSearch from "./SubmissionSearch";
import { useCurrentUser, useMyGroups } from "./useCurrentUser";
import VersionMenu, { type SubmissionVersion } from "./VersionMenu";
import WholeNotes from "./WholeNotes";

type Node = Record<string, unknown>;

// Renamed when the default changed, so every reviewer starts from it once
const KINDS_KEY = "iap.review.notesShown2";
const CONTENTS_OPEN_KEY = "iap.review.contentsOpen";
const SIDE_OPEN_KEY = "iap.review.sideOpen";
// How wide a panel folded to a rail is, as the contents and the submission under the review fold
// PRETOTYPE: the review over the whole workspace is a step in the browser's history, named in the address, so Back
// returns beside the page and a reload keeps it
const FULL_PARAM = "view";
const FULL_VIEW = "review";
const fullInAddress = () => new URLSearchParams(window.location.search).get(FULL_PARAM) === FULL_VIEW;
// How big the round buttons folding the panels away are
const FOCUSED_KEY = "iap.review.focused";

// PRETOTYPE: a form's items with the answers the data has, as those of an older version
function withValues(items: FormItem[], data: ReviewData): FormItem[] {
  return items.map(item => isQuestion(item) ? { ...item, value: data.answers.get(item.path)?.values ?? [] }
    : { ...item, items: withValues(item.items, data) });
}

// A form's items with only the questions kept, and the sections still holding any
function prune(items: FormItem[], keep: (path: string) => boolean): FormItem[] {
  return items.flatMap((item): FormItem[] => {
    if (isQuestion(item)) {
      return keep(item.path) ? [ item ] : [];
    }
    const inner = prune(item.items, keep);
    return inner.length > 0 ? [ { ...item, items: inner } ] : [];
  });
}

// The kinds of notes shown, remembered in the reviewer's browser
function storeKinds(kinds: Set<NoteKind>): Set<NoteKind> {
  try {
    localStorage.setItem(KINDS_KEY, JSON.stringify([ ...kinds ]));
  } catch {
    // Remembered for this page only
  }
  return kinds;
}

// Whether a side panel is open, remembered in the reviewer's browser; undefined until they choose
function useStoredFlag(key: string): [ boolean | undefined, (open: boolean) => void ] {
  const [ flag, setFlag ] = useState<boolean | undefined>(() => {
    try {
      const stored = localStorage.getItem(key);
      return stored === null ? undefined : stored === "true";
    } catch {
      return undefined;
    }
  });
  const store = useCallback((open: boolean) => {
    setFlag(open);
    try {
      localStorage.setItem(key, String(open));
    } catch {
      // Remembered for this page only
    }
  }, [ key ]);
  return [ flag, store ];
}

// Every kind until the reviewer chooses, but the extracted answers, since those agreeing with the answer add little
// and those that do not are said under the answer anyway; and other reviewers' issues, which are theirs to pursue
const DEFAULT_KINDS: NoteKind[] = NOTE_KINDS.map(info => info.kind).filter(kind => kind !== "otherIssue");
// The kinds always shown, which the reviewer cannot turn off: none, every kind toggled as the reviewer chooses
const ALWAYS: NoteKind[] = [];
// PRETOTYPE: what the page marks while the review is open: what the review lists, the AI's concerns, the answers the
// protocol contradicts, and the reviewer's own issues
const REVIEW_MARKS = new Set<NoteKind>([ "aiIssue", "contradiction", "issue" ]);

// What the filters' counts take in, not only the part being read, as their numbers alone may suggest
const COUNTED_ACROSS = "Counted across all the parts listed in the contents";
// The filters, by what they show: what the AI found, the issues that go to the study team, and the comments that
// stay among reviewers or with the reader. Each group named once, so its kinds can go by shorter names; the tooltip
// gives each kind's full one
const FILTER_GROUPS: { label: string; kinds: NoteKind[] }[] = [
  { label: "AI findings", kinds: [ "evidence", "contradiction", "aiIssue" ] },
  { label: "Issues", kinds: [ "issue", "otherIssue" ] },
  { label: "Comments", kinds: [ "discussion", "note" ] },
];
const SHORT_LABELS: Partial<Record<NoteKind, string>> = { evidence: "Linked answers", contradiction: "Mismatches",
  aiIssue: "Pre-review", issue: "Yours", otherIssue: "Others'", discussion: "Discussion" };

// Which of two parts shown one above the other
type Pane = "top" | "bottom";

// How wide the notes start, how narrow they may be dragged, and how much a part keeps beside them
const NOTES_WIDTH = 400;
const NOTES_LEAST = 240;
const PAGE_LEAST = 376;

// A document not being searched, as the part not worked in is, and what it tells back, nothing
const QUIET_SEARCH = { query: "", step: 0, previous: false };
const ignore = () => undefined;

// A share of a height, as kept in the browser
const isShare = (value: unknown): value is number => typeof value === "number" && value > 0 && value < 1;

const isFlag = (value: unknown): value is boolean => typeof value === "boolean";

// PRETOTYPE: what changed in a part since the submission was sent back, as its contents entry says it, and for a
// document, the versions to count its changes by
interface PartChange {
  shown: string;
  said: string;
  document?: { older: string; newer: string; uuid: string };
}

// Which tab the panel beside the page shows, the notes on what is read or the review
type SideTab = "notes" | "review";
const isSideTab = (value: unknown): value is SideTab => value === "notes" || value === "review";

// A width in pixels, as one kept in the browser should be
const isWidth = (value: unknown): value is number => typeof value === "number" && value > 0;

function storedKinds(): Set<NoteKind> {
  try {
    const stored = JSON.parse(localStorage.getItem(KINDS_KEY) ?? "null") as NoteKind[] | null;
    return new Set(stored ?? DEFAULT_KINDS);
  } catch {
    return new Set(DEFAULT_KINDS);
  }
}

// "29 notes: 18 extracted answers, 6 AI pre-review…", what a requirement's count is made of
function countText(notes: ReviewNote[]): string {
  const parts = NOTE_KINDS.map(info => [ info, notes.filter(note => note.kind === info.kind).length ] as const)
    .filter(([ , count ]) => count > 0)
    .map(([ info, count ]) => `${count} ${info.label.startsWith("AI") ? info.label : info.label.toLowerCase()}`);
  return `${notes.length} ${notes.length === 1 ? "note" : "notes"}: ${parts.join(", ")}`;
}

function questions(items: FormItem[]): FormQuestion[] {
  return items.flatMap(item => isQuestion(item) ? [ item ] : questions(item.items));
}


interface Loaded {
  submission: Node;
  form: SubmissionForm;
  approvals: Node[];
  // The forms every reviewer reads, whichever groups also draw on them
  everyone: Set<string>;
  // PRETOTYPE: the copies kept as reviewer groups sent it back, by the path of their version
  copies: Map<string, Node>;
  // PRETOTYPE: the section of a letter template issues on a document go in, by the document's requirement
  letterSections: Map<string, string>;
}


const text = (value: unknown): string => typeof value === "string" ? value : "";

async function readAll(doFetch: ReturnType<typeof useAuthenticatedFetch>, path: string): Promise<Loaded> {
  const read = async (url: string) => {
    const response = await doFetch(url);
    if (!response.ok) {
      throw new Error(`This submission could not be loaded (${response.status})`);
    }
    return response.json() as Promise<Node>;
  };
  const [ submission, form ] = await Promise.all([ read(`${path}.deep.json`), read(`${path}.form.json`) ]);
  const requirements = (form as unknown as SubmissionForm).requirements;
  const approvals = await Promise.all(requirements.filter(one => one.type === "sch/ApprovalRequirement")
    .map(one => read(`${one.path}.2.json`)));
  const version = typeof submission.schemaVersion === "object" && submission.schemaVersion !== null
    ? await read(`${text((submission.schemaVersion as Node)["@path"])}.1.json`) : {};
  const everyone = new Set(Object.entries(version)
    .filter(([ , part ]) => typeof part === "object" && part !== null && (part as Node).audience === "everyone")
    .map(([ name ]) => name));
  // A copy that cannot be read only leaves its comparison out
  const kept = [ ...new Set(Object.values(submission).map(child => typeof child === "object" && child !== null
    ? text((child as Node).sentBackCopy) : "").filter(Boolean)) ];
  const copies = new Map((await Promise.all(kept.map(copy => read(`${copy}/jcr:frozenNode.deep.json`)
    .then(node => [ copy, node ] as const, () => undefined))))
    .filter((one): one is readonly [ string, Node ] => one !== undefined));
  const letterSections = new Map(Object.entries(version)
    .filter(([ , part ]) => typeof part === "object" && part !== null && text((part as Node).letterSection))
    .map(([ name, part ]) => [ name, text((part as Node).letterSection) ]));
  return { submission, form: form as unknown as SubmissionForm, approvals, everyone, copies, letterSections };
}


// The answer to a question of a form, as its options call it
function answerTo(items: FormItem[], question: string): string {
  const asked = questions(items).find(item => item.path === question);
  return asked ? answerText(asked, asked.value) : "";
}

// PRETOTYPE. A submission as its reviewer works through it: its contents down the side, in the schema's order,
// each saying which reviewer groups it concerns, the reviewer's own stand out; the requirement they are on in the
// middle, compact for a form and whole for a document, with everything written about it, of the kinds they
// choose to see; their issues on the other side.
export default function ReviewScreen({ path }: { path: string }) {
  const doFetch = useAuthenticatedFetch();
  const me = useCurrentUser();
  const myGroups = useMyGroups(me);
  const theme = useTheme();
  const narrow = useMediaQuery(theme.breakpoints.down("md"));
  const [ loaded, setLoaded ] = useState<Loaded>();
  const [ error, setError ] = useState<string>();
  const [ selected, setSelected ] = useState<string>();
  // PRETOTYPE: a mark followed while the review is open, for the review to show where it lists it
  const [ revealed, setRevealed ] = useState<{ id: string; at: number }>();
  // PRETOTYPE: the review's contents folded away, to give its page the room; beside the page, its tabs go too, its page
  // saying where it is
  const [ listFolded, setListFolded ] = useStoredSetting("iap.review.listFolded", false, isFlag);
  const [ focusing, setFocusing ] = useState(false);
  const [ shown, setShown ] = useState(storedKinds);
  // Whether notes dismissed, resolved or addressed by an issue are shown, faded, rather than left out
  const [ showDone, setShowDone ] = useStoredSetting("iap.review.showDone", false, isFlag);
  // PRETOTYPE: whether what changed since the submission was last sent back is shown
  const [ showChanges, setShowChanges ] = useStoredSetting("iap.review.showChanges", true, isFlag);
  // A note done with that a link led to, shown all the same while it is the one selected
  const [ peek, setPeek ] = useState<string>();
  // PRETOTYPE: an older version of the submission being read instead of the latest, by the path of its copy
  const [ viewing, setViewing ] = useState<string>();
  // Going to a note by path, for the cards, set once what is shown is known
  const showNote = useRef<(id: string) => void>(undefined);
  // What is shown: the kinds the reviewer chose, and those always shown
  const showing = useMemo(() => new Set<NoteKind>([ ...shown, ...ALWAYS ]), [ shown ]);
  const [ mark, setMark ] = useState<{ id?: string; page?: number }>({});
  // PRETOTYPE: a second part shown under the first, to compare them, with its own selected note and page; the part
  // being worked in, whose notes are listed; and how much of the height the upper one takes
  const [ below, setBelow ] = useState<string>();
  const [ belowMark, setBelowMark ] = useState<{ id?: string; page?: number }>({});
  const [ active, setActive ] = useState<Pane>("top");
  const [ splitAt, setSplitAt ] = useStoredSetting("iap.review.splitAt", 0.5, isShare);
  // The column the notes of the part being worked in are listed in, the parts beside it, and its width
  const [ notesSlot, setNotesSlot ] = useState<HTMLElement | null>(null);
  // PRETOTYPE: of two parts, where each one's title bar takes a document's button writing about it
  const [ writeSlots, setWriteSlots ] = useState<Record<Pane, HTMLElement | null>>({ top: null, bottom: null });
  // One callback for each, kept from one render to the next, as React calls a new one with the slot again each time
  const writeSlotRefs = useMemo(() => {
    const keep = (pane: Pane) => (slot: HTMLElement | null) =>
      setWriteSlots(slots => (slots[pane] === slot ? slots : { ...slots, [pane]: slot }));
    return { top: keep("top"), bottom: keep("bottom") };
  }, []);
  const [ workArea, setWorkArea ] = useState<HTMLElement | null>(null);
  const [ paneColumn, setPaneColumn ] = useState<HTMLElement | null>(null);
  const [ workWidth, setWorkWidth ] = useState<number>();
  useEffect(() => {
    if (!workArea) {
      return undefined;
    }
    const observer = new ResizeObserver(([ entry ]) => setWorkWidth(entry.contentRect.width));
    observer.observe(workArea);
    return () => observer.disconnect();
  }, [ workArea ]);
  // How wide the notes beside a form's questions or a document are, as the reader last dragged them, everywhere
  const [ notesWidth, setNotesWidth ] = useStoredSetting("iap.review.notesWidth", NOTES_WIDTH, isWidth);
  // The contents entry being read, and, when it has scrolled out of the list, the edge it left by, where a copy of it
  // stays pinned
  const [ readEntry, setReadEntry ] = useState<HTMLElement | null>(null);
  const [ pinned, setPinned ] = useState<"above" | "below">();
  useEffect(() => {
    const root = readEntry?.closest<HTMLElement>("[data-panel-body]");
    if (!readEntry || !root) {
      return undefined;
    }
    const observer = new IntersectionObserver(([ seen ]) => setPinned(seen.isIntersecting ? undefined
      : seen.boundingClientRect.top < (seen.rootBounds?.top ?? 0) ? "above" : "below"), { root });
    observer.observe(readEntry);
    return () => observer.disconnect();
  }, [ readEntry ]);
  const [ dialog, setDialog ] = useState<"board" | "approve" | "sentLetter" | "changes">();
  const [ chosenTab, setSideTab ] = useStoredSetting<SideTab>("iap.review.sideTab", "notes", isSideTab);
  // Whether the review is shown over the whole workspace, its contents and page, rather than beside the page
  const [ reviewFull, setReviewFull ] = useState(fullInAddress);
  // Over the whole workspace, it is the review shown, whichever tab was chosen before, as on a reload
  const sideTab: SideTab = reviewFull ? "review" : chosenTab;
  // PRETOTYPE: how many changes the viewer showing a document counts, by the document, kept as it last said
  const [ changeCounts, setChangeCounts ] = useState<Record<string, number>>({});
  const countChanges = useCallback((uuid: string, count: number) =>
    setChangeCounts(counts => (counts[uuid] === count ? counts : { ...counts, [uuid]: count })), []);
  const leaveFull = useCallback(() => {
    setReviewFull(false);
  }, []);
  // Whether the step in the history that spread the review was taken on this page, rather than before a reload, so
  // that going back to it does not load the page again
  const spreadHere = useRef(false);
  // Back and Forward follow the address
  useEffect(() => {
    const follow = () => {
      spreadHere.current = fullInAddress() && spreadHere.current;
      if (fullInAddress()) {
        setReviewFull(true);
      } else {
        leaveFull();
      }
    };
    window.addEventListener("popstate", follow);
    return () => window.removeEventListener("popstate", follow);
  }, [ leaveFull ]);
  const spreadReview = useCallback((spread: boolean) => {
    const address = new URL(window.location.href);
    if (spread) {
      if (!fullInAddress()) {
        address.searchParams.set(FULL_PARAM, FULL_VIEW);
        window.history.pushState(null, "", address);
        spreadHere.current = true;
      }
      setSideTab("review");
      setReviewFull(true);
    } else if (spreadHere.current) {
      // Its own step in the history, undone as Back would
      spreadHere.current = false;
      window.history.back();
    } else {
      // Opened from the address itself, as on a reload: nothing on this page to go back to
      address.searchParams.delete(FULL_PARAM);
      window.history.replaceState(null, "", address);
      leaveFull();
    }
  }, [ leaveFull, setSideTab ]);
  // Esc leaves it too, unless it is closing something over it, or changing what is being written
  useEffect(() => {
    if (!reviewFull) {
      return undefined;
    }
    const escape = (event: KeyboardEvent) => {
      const target = event.target instanceof Element ? event.target : null;
      if (event.key === "Escape" && !event.defaultPrevented
        && !document.querySelector(".MuiModal-root:not(.MuiModal-hidden)")
        && !target?.closest("input, textarea, select, [contenteditable='true']")) {
        spreadReview(false);
      }
    };
    window.addEventListener("keydown", escape);
    return () => window.removeEventListener("keydown", escape);
  }, [ reviewFull, spreadReview ]);
  const full = reviewFull;
  const [ railOpen = true, setRailOpen ] = useStoredFlag(CONTENTS_OPEN_KEY);
  // Whether the panel of notes and review beside the page is open, as the contents on the other side can be folded
  const [ sideOpen = true, setSideOpen ] = useStoredFlag(SIDE_OPEN_KEY);
  // Over the whole workspace, or on a narrow screen where it is a tab of its own, it cannot be folded away
  const sideShown = sideOpen || full || narrow;
  // Whether the reviewer sees only what concerns them, as they start, or the whole submission
  const [ focused = true, setFocused ] = useStoredFlag(FOCUSED_KEY);
  const myName = usePersonName(me ?? "");
  const [ query, setQuery ] = useState("");

  // Bumped after anything is written, so the page reads the submission again
  const [ reloads, setReloads ] = useState(0);
  const load = useCallback(() => setReloads(current => current + 1), []);
  const retry = useCallback(() => {
    setError(undefined);
    load();
    return Promise.resolve();
  }, [ load ]);
  useEffect(() => {
    let current = true;
    readAll(doFetch, path)
      .then(read => current && setLoaded(read))
      .catch((failure: unknown) => current && setError(failure instanceof Error ? failure.message : String(failure)));
    return () => {
      current = false;
    };
  }, [ doFetch, path, reloads ]);

  const schema = loaded && typeof loaded.submission.schemaVersion === "object" && loaded.submission.schemaVersion
    ? String((loaded.submission.schemaVersion as Node)["@path"]) : "";
  const topics = useMemo(() => loaded ? parseTopics(loaded.approvals, schema) : new Map<string, Topic>(),
    [ loaded, schema ]);
  // PRETOTYPE: the copy kept when the reader's group sent the submission back, which documents and answers are
  // compared with
  const sentBack = useMemo(() => loaded ? sentBackCopy(loaded.submission, myGroups) : undefined, [ loaded, myGroups ]);
  const kept = useMemo(() => sentBack && loaded?.copies.get(sentBack.path), [ sentBack, loaded ]);
  // PRETOTYPE: the versions kept as reviewer groups sent the submission back, oldest first, and the one being read
  // instead of the latest, if any, with its copy
  const versions = useMemo(() => loaded ? keptVersions(loaded.submission) : [], [ loaded ]);
  const viewed = versions.find(one => one.path === viewing);
  const viewedCopy = viewed && loaded?.copies.get(viewed.path);
  // Compared with it always, a document with the version before its latest when there is no copy, so that notes say
  // how what they are about changed; the documents and forms show the changes only while the reviewer asks
  const copy = kept;
  const comparing = (document: ReviewedDocument) => viewed ? undefined : comparisonFor(document, kept);
  const sentBackAt = sentBack?.at;
  // What the AI read for each answer is shown as its source or a contradiction, or not at all
  // PRETOTYPE: the groups consulting for the approving ones, asking questions rather than requiring changes
  const consultingGroups = useMemo(() => new Set((loaded?.approvals ?? [])
    .filter(approval => approval.consulting === true).map(approval => text(approval.approverGroup))), [ loaded ]);
  const data: ReviewData | undefined = useMemo(() => loaded && me
    ? withAnswerChanges({ ...withNatures(withDone(withSent(withSources(
      parseReview(loaded.submission, schema, me, topics),
      loaded.form.requirements.filter(isFormRequirement).flatMap(one => questions(one.items))))), consultingGroups),
    before: copy && answersIn(copy, schema), changesShown: showChanges }, sentBackAt)
    : undefined, [ loaded, schema, me, topics, copy, sentBackAt, showChanges, consultingGroups ]);
  // PRETOTYPE: the questions whose answers changed since the submission was last sent back
  const changed = data ? changedAnswers(data) : new Set<string>();
  // What the pages and their notes show: what is done with only when the reviewer asks
  const peeking = peek !== undefined && (mark.id === peek || belowMark.id === peek) ? peek : undefined;
  // PRETOTYPE: what the pages, the contents and the notes show, the version being read, as it was then; the review
  // stays the latest's
  const seen = useMemo(() => data && viewed && viewedCopy ? asOfVersion(data, viewedCopy, schema, viewed.at) : data,
    [ data, viewed, viewedCopy, schema ]);
  const viewData: ReviewData | undefined = useMemo(() => seen && (showDone ? seen
    : { ...seen, notes: seen.notes.filter(note => !note.done || note.id === peeking) }), [ seen, showDone, peeking ]);
  // Each reviewer group as its approval calls it for short, to say whose another reviewer's issue is
  const groupLabels = useMemo(() => new Map((loaded?.approvals ?? []).map(approval =>
    [ text(approval.approverGroup), groupShortName(text(approval.label)) ] as const)), [ loaded ]);
  // The reviewer's group by its short name, when they review for only one
  const myGroupNames = (loaded?.approvals ?? []).filter(approval => myGroups.includes(text(approval.approverGroup)))
    .map(approval => groupShortName(text(approval.label)));
  const myGroupName = myGroupNames.length === 1 ? myGroupNames[0] : undefined;
  // The risks the reviewer's groups weigh, which their issues are filed under
  const reviewerTopics = useMemo(() => {
    const approvals = new Set((loaded?.approvals ?? [])
      .filter(approval => myGroups.includes(text(approval.approverGroup))).map(approval => text(approval["@name"])));
    return { mine: [ ...topics.values() ].filter(topic => approvals.has(topic.approval)), all: topics };
  }, [ loaded, myGroups, topics ]);
  const groups = useMemo(() => loaded ? parseGroups(loaded.approvals, schema) : new Map<string, ReviewGroup[]>(),
    [ loaded, schema ]);
  // Focused, every question shown is the reviewer's, so only the other groups asking it are named on it
  const othersGroups = useMemo(() => new Map([ ...groups ].map(([ part, asking ]) =>
    [ part, asking.filter(one => !myGroups.includes(one.group)) ] as const)), [ groups, myGroups ]);
  const requirements = useMemo(() => (loaded?.form.requirements ?? []).filter(one =>
    isFormRequirement(one) || (isDocumentRequirement(one) && data?.documents.has(one.name))), [ loaded, data ]);
  const everyone = loaded?.everyone;
  const minePart = useCallback((part: string) => Boolean(everyone?.has(part.split("/")[0]))
    || isMine(part, groups, myGroups), [ everyone, groups, myGroups ]);
  const mine = useCallback((requirement: Requirement) => isFormRequirement(requirement)
    ? questions(requirement.items).some(question => minePart(question.path))
    : minePart(requirement.name), [ minePart ]);
  // Focused, each form keeps only the questions concerning the reviewer, and the contents only the requirements
  // that keep any, or the document concerning them; and the one being read, however they got there
  const trimmed = useMemo(() => focused ? requirements.map(one => isFormRequirement(one)
    ? { ...one, items: prune(one.items, minePart) } : one) : requirements, [ focused, requirements, minePart ]);
  // The first requirement concerning the reviewer, until they choose another
  const top = trimmed.find(one => one.name === selected) ?? trimmed.find(mine) ?? trimmed.at(0);
  const bottom = below && below !== top?.name && !narrow ? trimmed.find(one => one.name === below) : undefined;
  // The part being worked in: the one the search, the contents and the notes listed follow
  const current = active === "bottom" && bottom ? bottom : top;
  const listed = focused ? trimmed.filter(one => one === top || one === bottom || mine(one)) : trimmed;

  // The reviewer groups a requirement concerns: those any of its questions, or the document, matters to
  const groupsOf = (requirement: Requirement): ReviewGroup[] | undefined => {
    const parts = isFormRequirement(requirement) ? questions(requirement.items).map(question => question.path)
      : [ requirement.name ];
    const found = new Map<string, ReviewGroup>();
    parts.forEach(part => groups.get(part)?.forEach(group => found.set(group.name, group)));
    return found.size > 0 ? [ ...found.values() ] : undefined;
  };

  // PRETOTYPE: what changed in a part since the submission was last sent back, as its contents entry says it: a document's
  // new version, with the versions to count its changes by, or how many of a form's answers changed
  const changeOf = (requirement: Requirement): PartChange | undefined => {
    if (!data || !showChanges || viewed) {
      return undefined;
    }
    if (isFormRequirement(requirement)) {
      const many = questions(requirement.items).filter(question => changed.has(question.path)).length;
      return many > 0 ? { shown: String(many),
        said: `${many} ${many === 1 ? "answer" : "answers"} changed since it was sent back` } : undefined;
    }
    const document = data.documents.get(requirement.name);
    const compared = document && comparing(document);
    return compared ? { shown: "New", said: `${compared.current}, newer than the one sent back`,
      document: { older: compared.url, newer: document.url, uuid: document.uuid } } : undefined;
  };
  // What each part has to read, counted in the contents: a note done with that a link shows meanwhile is not
  const notesOf = useCallback((requirement: Requirement, withDone = showDone): ReviewNote[] => {
    if (!seen) {
      return [];
    }
    const counted = seen.notes.filter(note => withDone || !note.done);
    if (isFormRequirement(requirement)) {
      const uuids = questions(requirement.items)
        .map(question => seen.answers.get(question.path)?.uuid).filter((one): one is string => Boolean(one));
      return notesAbout(counted, uuids);
    }
    const document = seen.documents.get(requirement.name);
    return document
      ? notesAbout(counted, [ document.uuid, ...document.versions.map(one => one.uuid) ])
        .filter(note => note.passage !== undefined || note.subjects.includes(document.uuid))
      : [];
  }, [ seen, showDone ]);
  // The reviewers' discussion and the reviewer's notes to self on the whole submission, rather than on a question or
  // passage, as the queue shows them; those done with only when the reviewer asks, as on the parts
  const wholeNotes = ((viewData ?? data)?.notes ?? []).filter(note => (note.kind === "discussion"
    || note.kind === "note") && note.subjects.length === 0 && !note.passage && (showDone || !note.done));
  // What the filters count: the notes of the parts the contents list, each once though it is about two of them, as a
  // contradiction is about an answer and the protocol, and those on the whole submission
  const listedNotes = [ ...new Map([ ...listed.flatMap(one => notesOf(one, true)), ...wholeNotes ]
    .map(note => [ note.id, note ])).values() ];
  const kindCount = (kind: NoteKind) => listedNotes.filter(note => note.kind === kind && (showDone || !note.done))
    .length;


  // The reviewer group the reviewer reviews this submission for
  const myGroup = (loaded?.approvals ?? []).map(approval => text(approval.approverGroup))
    .find(group => myGroups.includes(group));
  // PRETOTYPE: whether the reviewer's group consults for the approving ones, and so what their new issues are unless
  // they say otherwise
  const consulting = consultingGroups.has(myGroup ?? "");
  // The risks, and the type a new issue has unless the reviewer says otherwise, for the note editors
  const topicsWritten = useMemo(() => ({ ...reviewerTopics, nature: defaultNature(consulting) }),
    [ reviewerTopics, consulting ]);
  const actions: ReviewActions = useMemo(() => ({
    write: async fields => {
      await writeComment(doFetch, path, fields.kind === "issue" ? { ...myGroup ? { group: myGroup } : {},
        nature: defaultNature(consulting), ...fields } : fields);
      // A new note of a kind the reviewer hides shows that kind again, rather than vanishing as it is added
      const written = WRITTEN.find(kind => kind === fields.kind);
      if (written) {
        setShown(current => current.has(written) ? current : storeKinds(new Set([ ...current, written ])));
      }
      load();
    },
    show: id => showNote.current?.(id),
  }), [ doFetch, path, load, myGroup, consulting ]);
  // PRETOTYPE: an older version is only read, nothing written in it
  const pageActions = useMemo(() => viewed ? { ...actions, readOnly: true } : actions, [ actions, viewed ]);
  // PRETOTYPE: a form's questions with their answers in the version being read
  const answered = (items: FormItem[]): FormItem[] => seen && viewed ? withValues(items, seen) : items;

  const questionLabel = useCallback((question: string | undefined, extracted: string | undefined) => {
    const asked = requirements.filter(isFormRequirement).flatMap(one => questions(one.items))
      .find(one => one.path === question);
    const form = requirements.filter(isFormRequirement)
      .find(one => questions(one.items).some(each => each.path === question))?.label;
    return { text: asked?.text ?? "", answer: asked && extracted !== undefined
      ? answerText(asked, extracted.split(";").map(one => one.trim())) : undefined,
    given: asked ? answerText(asked, asked.value) : undefined, form };
  }, [ requirements ]);

  const toggleKind = (kind: NoteKind) => setShown(current => {
    const next = new Set(current);
    if (next.has(kind)) {
      next.delete(kind);
    } else {
      next.add(kind);
    }
    return storeKinds(next);
  });

  // Closing either part leaves the other alone, as it was
  const close = (pane: Pane) => {
    if (pane === "top" && bottom) {
      setSelected(bottom.name);
      setMark(belowMark);
    }
    setBelow(undefined);
    setBelowMark({});
    setActive("top");
  };
  const openBelow = (requirement: string) => {
    if (requirement !== top?.name) {
      setBelow(requirement);
      setBelowMark({});
      setActive("bottom");
    }
  };

  // Going to a part: where it is shown already, that part is worked in; otherwise it takes the place of the one
  // being worked in, or, following a link from one part to another, opens in the other place, to compare them
  const go = (requirement: string, focus?: { id?: string; page?: number; question?: string }, beside = false) => {
    const shownIn: Pane | undefined = top?.name === requirement ? "top"
      : bottom?.name === requirement ? "bottom" : undefined;
    const pane: Pane = shownIn ?? (beside && !narrow ? (active === "top" ? "bottom" : "top") : active);
    if (pane === "top") {
      setSelected(requirement);
      setMark({ id: focus?.id, page: focus?.page });
    } else {
      setBelow(requirement);
      setBelowMark({ id: focus?.id, page: focus?.page });
    }
    setActive(pane);
    if (focus?.question) {
      const target = focus.question;
      setTimeout(() => document.getElementById(`question-${target}`)?.scrollIntoView({ behavior: "smooth" }), 50);
    } else if (!shownIn) {
      window.scrollTo({ top: 0 });
      (document.querySelector(`[data-pane="${pane}"] [data-form-scroller]`)
        ?? document.querySelector("[data-form-scroller]"))?.scrollTo({ top: 0 });
    }
  };

  // What "Show where" leads to is shown, whatever the filters hide, and pulses once there: a note's kind is shown
  // again, and a question outside the reviewer's focus brings the whole submission back
  const reveal = (note: ReviewNote, question?: string) => {
    setShown(kinds => kinds.has(note.kind) ? kinds : storeKinds(new Set([ ...kinds, note.kind ])));
    if (question && focused && !minePart(question)) {
      setFocused(false);
    }
    pulseWhenThere(() => {
      const card = document.querySelector<HTMLElement>(`[data-note="${CSS.escape(note.id)}"]`);
      return card?.closest<HTMLElement>("li") ?? card
        ?? (question ? document.getElementById(`question-${question}`) : null);
    });
  };

  // From a passage to the answer in the form it backs or contradicts: a contradiction selected among the form's notes,
  // and the answer outlined a moment, as "Show where" does
  const openAnswer = (note: ReviewNote) => {
    const question = note.question;
    if (question) {
      reveal(note, question);
      go(question.split("/")[0], { id: note.kind === "contradiction" ? note.id : undefined, question }, true);
    }
  };

  const openEvidence = (note: ReviewNote) => {
    const source = note.passage?.source;
    const document = [ ...data?.documents.entries() ?? [] ].find(([ , one ]) => one.uuid === source
      || one.versions.some(version => version.uuid === source))?.[0] ?? "protocol";
    reveal(note);
    go(document, { id: note.id, page: note.passage?.page }, true);
  };

  // The submission search, found in place as a browser finds in a page: where its terms occur in each requirement,
  // and the occurrence it is on. Forms are searched as they are typed, documents once their text is read.
  const terms = query.trim().length >= 2 ? query.trim() : "";
  const [ pageHits, setPageHits ] = useState<{ terms: string; counts: Map<string, number[]> }>();
  useEffect(() => {
    if (!terms || !data) {
      return;
    }
    let current = true;
    const timer = setTimeout(() => {
      void Promise.all([ ...data.documents.entries() ].map(async ([ name, document ]) =>
        [ name, pageCounts(await documentText(doFetch, document.url), terms) ] as const))
        .then(all => current && setPageHits({ terms, counts: new Map(all) }));
    }, 200);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [ terms, data, doFetch ]);
  const formHits = useMemo(() => new Map(terms ? listed.filter(isFormRequirement)
    .map(one => [ one.name, formMatches(one.items, terms, question => answerTo(one.items, question)) ]) : []),
  [ terms, listed ]);
  const documentHits = pageHits?.terms === terms ? pageHits.counts : undefined;
  // How many times a requirement has the terms: undefined while not searching, or while its text is still read
  const hitCount = (requirement: Requirement): number | undefined => !terms ? undefined
    : isFormRequirement(requirement) ? formHits.get(requirement.name)?.length ?? 0
      : documentHits?.get(requirement.name)?.reduce((sum, one) => sum + one, 0);
  const [ formAt, setFormAt ] = useState<{ terms: string; requirement: string; index: number }>();
  const [ documentStep, setDocumentStep ] = useState({ step: 0, previous: false });
  const [ documentFound, setDocumentFound ] = useState<DocumentSearch>();
  const activeHit = formAt?.terms === terms && formAt.requirement === current?.name
    ? formHits.get(formAt.requirement)?.[formAt.index] : undefined;
  const searchContext = useMemo(() => ({ terms, active: activeHit }), [ terms, activeHit ]);
  // The occurrence the search is on, brought into view
  useEffect(() => {
    if (activeHit) {
      document.querySelector("[data-search-current]")?.scrollIntoView({ block: "center", behavior: "smooth" });
    }
  }, [ activeHit ]);

  // Typing looks from where the reviewer is: the first question still in view on a form, the page shown on a
  // document. It never takes them to another requirement; the counts in the contents say where else to look.
  const typeQuery = (next: string) => {
    setQuery(next);
    const nextTerms = next.trim().length >= 2 ? next.trim() : "";
    if (!nextTerms || !current || !isFormRequirement(current)) {
      return;
    }
    const matches = formMatches(current.items, nextTerms, question => answerTo(current.items, question));
    const asked = questions(current.items).map(question => question.path);
    const top = (document.querySelector("header")?.getBoundingClientRect().bottom ?? 0) + 8;
    const inView = asked.findIndex(question =>
      (document.getElementById(`question-${question}`)?.getBoundingClientRect().bottom ?? 0) > top);
    const from = matches.findIndex(match => asked.indexOf(match.question) >= Math.max(inView, 0));
    setFormAt(matches.length > 0 ? { terms: nextTerms, requirement: current.name, index: Math.max(from, 0) }
      : undefined);
  };

  const counts = listed.map(one => hitCount(one) ?? 0);
  const total = counts.reduce((sum, one) => sum + one, 0);
  const at = current ? listed.indexOf(current) : -1;
  const here = at >= 0 ? counts[at] : 0;
  const local = !current || here === 0 ? undefined
    : isFormRequirement(current) ? (activeHit && formAt ? formAt.index + 1 : undefined)
      : documentFound?.outcome === "found" && documentFound.current > 0 ? Math.min(documentFound.current, here)
        : undefined;
  const position = local === undefined ? undefined : counts.slice(0, at).reduce((sum, one) => sum + one, 0) + local;

  // Into a requirement with occurrences, at its first one, or its last when going back
  const enter = (requirement: Requirement, fromEnd: boolean) => {
    const count = hitCount(requirement) ?? 0;
    if (isFormRequirement(requirement)) {
      go(requirement.name);
      setFormAt({ terms, requirement: requirement.name, index: fromEnd ? count - 1 : 0 });
      return;
    }
    const perPage = documentHits?.get(requirement.name) ?? [];
    const page = (fromEnd ? perPage.findLastIndex(one => one > 0) : perPage.findIndex(one => one > 0)) + 1;
    setDocumentFound(undefined);
    go(requirement.name, { page: page > 0 ? page : undefined });
  };
  // The next occurrence, or the previous: in the requirement shown while it has more, then in the next one that has
  // any, round to the first
  const step = (previous: boolean) => {
    if (!terms || !current || total === 0) {
      return;
    }
    if (here > 0 && isFormRequirement(current)) {
      const index = activeHit && formAt ? formAt.index + (previous ? -1 : 1) : (previous ? here - 1 : 0);
      if (index >= 0 && index < here) {
        setFormAt({ terms, requirement: current.name, index });
        return;
      }
    } else if (here > 0 && documentFound?.outcome === "found"
      && (previous ? documentFound.current > 1 : documentFound.current < documentFound.total)) {
      setDocumentStep(last => ({ step: last.step + 1, previous }));
      return;
    }
    for (let away = 1; away <= listed.length; away++) {
      const next = (at + (previous ? -away : away) + listed.length * 2) % listed.length;
      if (counts[next] > 0) {
        enter(listed[next], previous);
        return;
      }
    }
  };

  const search = (inBar: boolean) => (
    <SubmissionSearch
      query={query} inBar={inBar} onQuery={typeQuery} onStep={step}
      position={position} total={terms ? total : undefined}
      reading={Boolean(terms) && documentHits === undefined && (data?.documents.size ?? 0) > 0}
    />
  );

  // PRETOTYPE: on a submission answered since it was sent back, which version is read, by the day it came back
  const backAt = loaded && sentBack ? resubmittedAt(loaded.submission) : undefined;
  // PRETOTYPE: a newer version that came in after another group sent the submission back, while the reviewer's own
  // review started on the earlier one
  const lastKept = versions.at(-1);
  const newerSince = lastKept && backAt && !myGroups.includes(lastKept.group)
    && Date.parse(backAt) > Date.parse(lastKept.at) ? { at: backAt, sentBack: lastKept.at, group: lastKept.group }
    : undefined;
  const version = backAt && sentBack && Date.parse(backAt) > Date.parse(sentBack.at)
    ? `Version of ${shortDate(backAt)}` : undefined;
  // PRETOTYPE: the versions the reviewer can read, newest first: the latest, then each kept as it was sent back
  // named by their day, and by their time too where two share a day, as when sent back and answered the same day
  const days = [ backAt, ...versions.map(one => one.at) ].filter(Boolean).map(at => shortDate(at ?? ""));
  const versionName = (at: string) => `Version of ${shortDate(at)}${days.filter(day => day === shortDate(at)).length > 1
    ? `, ${new Date(at).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" })}` : ""}`;
  const versionList: SubmissionVersion[] = version && backAt ? [ { label: versionName(backAt),
    detail: "The latest, being reviewed" },
  ...[ ...versions ].reverse().map(one => ({ id: one.path, label: versionName(one.at),
    detail: `As ${groupLabels.get(one.group) ?? "a reviewer group"} sent it back` })) ] : [];
  const versionMenu = versionList.length > 1
    ? <VersionMenu versions={versionList} viewing={viewed?.path} onView={setViewing} /> : undefined;
  // PRETOTYPE: whether what changed since is shown is chosen on each part, a document in its viewer's menu and a form
  // by a switch beside its title, for the whole submission
  const comparable = Boolean(sentBack && kept && version && !viewed);
  // search beside them on wide screens
  const header = loaded?.submission;
  usePublishedPageContext(header && {
    title: text(header.title),
    short: text(header.identifier),
    // The version before the person, whose name gives way first where the bar is short
    details: text(header.pi),
    version: versionMenu,
    badges: <><TagChip tags={header.tags} category="lifecycle" /><TagChip tags={header.tags} category="priority" /></>,
    end: <Countdown due={header.reviewDue} pausedAt={header.reviewPausedAt} variant="caption" />,
    tools: search(true),
    workspace: true,
  });

  // What an issue is about, as the study team would recognize it: the question or the document
  const about = (note: ReviewNote): string => {
    const part = note.subjects.map(subject => data?.partOf.get(subject)).find(Boolean);
    if (!part) {
      return "";
    }
    const asked = requirements.filter(isFormRequirement).flatMap(one => questions(one.items))
      .find(one => one.path === part);
    return asked?.text ?? requirements.find(one => one.name === part)?.label ?? "";
  };

  // Where a note was written: of the passage it quotes and the answers and documents it is about, one already shown,
  // the one being worked in first, or else the passage, which then takes the place of the part being worked in
  const openIssue = (note: ReviewNote) => {
    const places = [ note.passage?.source, ...note.subjects ].map(uuid => uuid && data?.partOf.get(uuid))
      .filter((part): part is string => Boolean(part));
    const requirementOf = (part: string) => data?.documents.has(part) ? part : part.split("/")[0];
    const panes = active === "top" ? [ top, bottom ] : [ bottom, top ];
    const part = panes.map(pane => places.find(one => requirementOf(one) === pane?.name)).find(Boolean)
      ?? places.at(0);
    if (!part) {
      return;
    }
    if (note.done) {
      setPeek(note.id);
    }
    if (data?.documents.has(part)) {
      reveal(note);
      go(part, { id: note.id, page: note.passage?.page });
    } else {
      reveal(note, part);
      go(part.split("/")[0], { id: note.id, question: part });
    }
  };
  // From an issue to the note it was made of, and back
  useEffect(() => {
    showNote.current = (id: string) => {
      const note = data?.notes.find(one => one.id === id);
      if (note) {
        openIssue(note);
      }
    };
  });

  // The reviewer's own review task: the open one their groups perform
  const myTaskNode = (): Node | undefined => {
    const instances = loaded?.submission["wf:instances"];
    for (const instance of Object.values(typeof instances === "object" && instances ? instances : {})) {
      for (const task of Object.values(typeof instance === "object" && instance ? instance as Node : {})) {
        const node = typeof task === "object" && task ? task as Node : undefined;
        const performers = Array.isArray(node?.performers) ? node.performers.map(String) : [];
        if (node?.["jcr:primaryType"] === "wf:TaskInstance" && [ "created", "active" ].includes(text(node.status))
          && performers.some(one => myGroups.includes(one))) {
          return node;
        }
      }
    }
    return undefined;
  };
  const myTask = (): string | undefined => text(myTaskNode()?.["@path"]) || undefined;
  // Completes the reviewer's task with a decision, through the workflow
  const decide = async (outcome: string, note?: string) => {
    const task = myTask();
    if (!task) {
      throw new Error("Your review is not waiting on a decision now.");
    }
    const body = new FormData();
    body.append("outcome", outcome);
    if (note) {
      body.append("outcomeNote", note);
    }
    const response = await doFetch(`${task}.json`, { method: "POST", body });
    if (!response.ok) {
      throw new Error(`That could not be done (${response.status})`);
    }
    load();
  };
  const sendLetter = (letter: string) => decide("changes-requested", letter);
  // The reviewer's group, as its approval calls it
  const myGroupLabel = (loaded?.form.requirements ?? []).filter(isApprovalRequirement)
    .find(one => myGroups.includes(one.approverGroup))?.label ?? "";

  if (error) {
    return <LoadError title="This submission could not be loaded" message={error} onRetry={retry} />;
  }
  if (!loaded || !data || !me) {
    return <LoadingOverlay open message="Loading the submission" />;
  }
  const submission = loaded.submission;
  // The reviewer's own issues: what their panel lists, and what their letter and escalation are made of
  const myIssues = data.notes.filter(note => note.kind === "issue" && note.mine);
  // The review, risk by risk: the AI's read and open concerns, the reviewer's issues, and whether considered
  const asked = requirements.filter(isFormRequirement).flatMap(one => questions(one.items));
  // The answers a risk draws on that the protocol contradicts, by what was read from it
  const mismatchesOf = (topic: Topic): Mismatch[] => asked.filter(question => topic.sources.includes(question.path))
    .flatMap(question => {
      const evidence = data.notes.find(note => note.kind === "contradiction" && !note.done
        && note.question === question.path);
      return evidence ? [ {
        question: question.text, answer: answerText(question, question.value),
        protocol: answerText(question, evidence.extracted?.split(";").map(one => one.trim()) ?? []), evidence,
      } ] : [];
    });
  const risks: RiskReview[] = reviewerTopics.mine.map(topic => ({
    topic, assessment: data.assessments.get(topic.path),
    aiOpen: data.notes.filter(note => note.kind === "aiIssue" && !note.done && note.topicPath === topic.path),
    aiSettled: data.settledAi.filter(note => note.topicPath === topic.path),
    mismatches: mismatchesOf(topic),
    issues: myIssues.filter(note => note.topicPath === topic.path),
    considered: data.considered.has(topic.path),
  }));
  const unfiled = myIssues.filter(note => !reviewerTopics.mine.some(topic => topic.path === note.topicPath));
  // PRETOTYPE: while the review is open, the page marks only what it lists, and a mark followed leads there
  const reviewing = sideTab === "review";
  const inReview = new Set([ ...risks.flatMap(risk => [ ...risk.aiOpen, ...risk.issues,
    ...risk.mismatches.map(one => one.evidence) ]), ...unfiled ].map(note => note.id));
  const shownData = viewData ?? data;
  const pageData = reviewing ? { ...shownData, notes: shownData.notes.filter(note => inReview.has(note.id)) }
    : shownData;
  const pageShown = reviewing ? REVIEW_MARKS : showing;
  const follow = (id: string | undefined) => {
    if (reviewing && id) {
      setRevealed({ id, at: Date.now() });
    }
  };
  const wholeKinds = ([ "discussion", "note" ] as const).filter(kind => shown.has(kind));
  // What other reviewers raised, by group, and how much of it on what this reviewer also reviews
  const othersRaised: OthersRaised[] = [ ...Map.groupBy(data.notes.filter(note => note.kind === "otherIssue"),
    note => groupLabels.get(note.group ?? "") ?? "Another group") ].map(([ group, raised ]) => ({
    group, count: raised.length,
    shared: raised.filter(note => note.subjects.some(subject => minePart(data.partOf.get(subject) ?? ""))).length,
  }));
  // What the reviewer can do from their review
  const doing: ReviewDoing = {
    // On a narrow screen, the review is shown instead of the page, so going to where something was raised leaves it
    open: note => {
      if (narrow) {
        setSideTab("notes");
      }
      openIssue(note);
    },
    consider: (topic, considered) => actions.write(considered ? { consider: topic } : { unconsider: topic }),
    makeIssue: (note, said, severity, topic, nature) =>
      raiseAsIssue(actions.write, note, said, severity, topic, nature),
    dismiss: note => actions.write({ dismiss: note.id }),
    undismiss: note => actions.write({ undismiss: note.id }),
    defaultNature: defaultNature(consulting),
    addIssue: (topic, said, severity, nature) => actions.write({ kind: "issue", text: said,
      ...severity ? { severity } : {}, ...topic ? { topic } : {}, ...nature ? { nature } : {} }),
    editIssue: (note, said, severity, topic, nature) => actions.write({ edit: note.id, text: said,
      ...severity ? { severity } : {}, ...topic !== undefined ? { topic } : {}, ...nature ? { nature } : {} }),
    removeIssue: note => actions.write({ remove: note.id }),
    reply: (note, said) => actions.write({ replyTo: note.id, text: said }),
    editReply: (reply, said) => actions.write({ edit: reply, text: said }),
    removeReply: reply => actions.write({ remove: reply }),
    showOthers: () => setShown(kinds => storeKinds(new Set([ ...kinds, "otherIssue" ]))),
  };
  const waiting = myTask() !== undefined;
  // A suggestion can go with an approval; anything more serious needs changes first
  // Resolved issues are asked no more: the letter, the escalation and what holds Approve back are the open ones
  const openIssues = myIssues.filter(note => !note.done);
  // PRETOTYPE: what holds approving back is a change still required; a suggestion goes with the approval; a question
  // does not hold it back, but one never sent is said
  const blocking = openIssues.filter(note => note.nature === "required-change");
  const suggestions = openIssues.filter(note => note.nature === "suggestion");
  const unsentQuestions = openIssues.filter(note => note.nature === "question" && !note.sent);
  const letterInput = { title: text(submission.title), identifier: text(submission.identifier),
    pi: text(submission.pi), about, issues: openIssues,
    risks: reviewerTopics.mine.map(topic => ({ path: topic.path, label: topic.label })),
    // The letter says "the … review", which some groups' names already end with
    reviewer: myName, group: myGroupLabel.toLowerCase().replace(/ review$/, ""),
    template: text(loaded.approvals.find(one => myGroups.includes(text(one.approverGroup)))?.letterTemplate)
      || undefined,
    sectionOf: (issue: ReviewNote) => [ ...loaded.letterSections ]
      .find(([ name ]) => {
        const document = data.documents.get(name);
        return document && [ document.uuid, ...document.versions.map(one => one.uuid) ]
          .some(uuid => issue.subjects.includes(uuid));
      })?.[1] ?? (issue.topicPath ? topics.get(issue.topicPath)?.letterSection : undefined) };
  // The letter asking for changes, as the reviewer's open issues say it now
  const letter = draftLetter(letterInput);
  // What the reviewer last decided, said once it is with someone else, or when it comes back after changes
  const decidedOn = data.decided
    && new Date(data.decided.at).toLocaleDateString(undefined, { month: "short", day: "numeric" });
  // PRETOTYPE: the REB writes a letter asking for changes; the other groups write to the study team with their
  // questions and changes, in an email from the platform; a group consulting for the approving ones finishes its
  // review rather than approving
  const lettered = letterInput.template !== undefined;
  const wrote = lettered ? "You requested changes" : "You wrote to the study team";
  const decided = !data.decided ? undefined
    : data.decided.outcome === "approved"
      ? `${consulting ? "You finished your review" : "You approved this"} on ${decidedOn}.`
      : data.decided.outcome === "rejected" ? `You rejected this on ${decidedOn}.`
        : waiting ? `${wrote} on ${decidedOn}, and the study team has answered.`
          : `${wrote} on ${decidedOn}. It's with the study team now.`;
  const email = issuesEmail({ review: myGroupLabel, identifier: text(submission.identifier),
    title: text(submission.title), pi: text(submission.pi), issues: openIssues, about });
  const lastLetter = data.letters.at(-1);
  const reviewActions = {
    waiting,
    decided: !waiting || data.decided?.outcome === "changes-requested" ? decided : undefined,
    outcome: data.decided?.outcome,
    readLetter: lastLetter ? () => setDialog("sentLetter") : undefined,
    // Asking for changes shows what the study team will read before it is sent: the REB's Letter of Review, or the
    // other groups' issues as a letter of their own
    send: () => setDialog("changes"),
    sendLabel: lettered ? "Request changes" : "Send to the study team",
    sendBlocked: openIssues.length === 0 ? "You have no open issues to send" : undefined,
    approveLabel: consulting ? "Finish my review" : "Approve",
    approve: () => setDialog("approve"),
    approveBlocked: !waiting ? "Your review is not waiting on a decision now"
      : blocking.length > 0
        ? `${lettered ? "Request changes for" : "Send the study team"} your ${blocking.length === 1
          ? "required change" : `${blocking.length} required changes`} first`
        : undefined,
    board: myGroups.includes("reb-reviewers") ? () => setDialog("board") : undefined,
    // PRETOTYPE: decided on the latest version only
    blocked: viewed ? "Go back to the latest version to decide" : undefined,
  };
  // What is still open in the review, said before approving
  const unconsidered = risks.filter(risk => !risk.considered).length;
  const aiOpen = risks.reduce((sum, risk) => sum + risk.aiOpen.length, 0);
  const contradicted = new Set(risks.flatMap(risk => risk.mismatches.map(one => one.question))).size;
  // The title of the page being read, heading the form's or the document's own column, beside its notes
  // PRETOTYPE: beside a form's title, whether what changed in its answers since the submission was sent back is shown,
  // for every part, as a document's viewer menu also chooses; only on a form with answers that changed
  const changesSwitch = (requirement: Requirement) => {
    const many = comparable && isFormRequirement(requirement)
      ? questions(requirement.items).filter(question => changed.has(question.path)).length : 0;
    return many > 0 && (
      <FormControlLabel
        label={`Show ${many === 1 ? "1 change" : `${many} changes`}`}
        control={<Switch size="small" checked={showChanges} onChange={event => setShowChanges(event.target.checked)} />}
        sx={{ mr: 0, flexShrink: 0, "& .MuiFormControlLabel-label": { typography: "body2" } }}
      />
    );
  };
  const withChangesSwitch = (requirement: Requirement, heading: ReactNode) => {
    const control = changesSwitch(requirement);
    return control ? (
      <Stack direction="row" sx={{ alignItems: "center", gap: 2 }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>{heading}</Box>
        {control}
      </Stack>
    ) : heading;
  };
  // How wide the notes are, as far as the panel has room for them beside a part
  const notesShown = workWidth === undefined ? notesWidth
    : Math.max(NOTES_LEAST, Math.min(notesWidth, workWidth - PAGE_LEAST));
  // A part of the submission, one of the two when a second is open: its title, with a button closing it then, and the
  // form or document; the one being worked in lists its notes, and, of two, is marked down its side
  const part = (pane: Pane, requirement: Requirement) => {
    const isActive = active === pane;
    const paneMark = pane === "top" ? mark : belowMark;
    const setPaneMark = pane === "top" ? setMark : setBelowMark;
    // Alone, a part is headed by its title; of two, each has a title bar, as a dialog has, with the button closing it
    // in its corner. The one whose notes are listed is ringed, its bar on their tint and its title bold in its colour;
    // the other's title stays as readable, only not bold
    const title = withChangesSwitch(requirement,
      <Typography variant="h6" component="h2" sx={{ mb: 0.5 }}>{requirement.label}</Typography>);
    const bar = bottom && (
      <Stack direction="row" sx={{ alignItems: "center", gap: 1, pl: 2, pr: 1, py: 0.5, flexShrink: 0,
        borderBottom: 1, borderColor: "divider", bgcolor: "background.paper" }}>
        <Typography variant="h6" component="h2"
          sx={{ flex: 1, minWidth: 0, ...isActive ? { color: "primary.main" } : { fontWeight: "fontWeightRegular" } }}>
          {requirement.label}
        </Typography>
        {changesSwitch(requirement)}
        {/* A document's button writing about it as a whole, which it puts here */}
        <Box ref={writeSlotRefs[pane]} sx={{ display: "flex" }} />
        <Tooltip title="Close" disableInteractive>
          <IconButton aria-label={`Close ${requirement.label}`} onClick={() => close(pane)}>
            <CloseOutlined />
          </IconButton>
        </Tooltip>
      </Stack>
    );
    const heading = bar ? undefined : title;
    const document = isDocumentRequirement(requirement) ? seen?.documents.get(requirement.name) : undefined;
    return (
      <Box
        data-pane={pane} onPointerDownCapture={() => setActive(pane)} onFocusCapture={() => setActive(pane)}
        sx={{ flex: bottom ? `${pane === "top" ? splitAt : 1 - splitAt} 1 0` : "1 1 0", minHeight: 0,
          display: "flex", flexDirection: "column", position: "relative",
          // Of two, the one worked in ringed, drawn over what it holds, its scrollbars too
          ...bottom && isActive ? { "&::after": { content: "\"\"", position: "absolute", inset: 2, zIndex: 2,
            pointerEvents: "none", border: 2, borderColor: "primary.main", borderRadius: 1.5,
            // The room left outside it painted as the page, so nothing the part holds shows past it
            boxShadow: theme => `0 0 0 2px ${(theme.vars ?? theme).palette.background.paper}` } } : {} }}
      >
        {bar}
        {isFormRequirement(requirement) && (
          <Box sx={{ flex: 1, minHeight: 0 }}>
            <SearchContext.Provider value={searchContext}>
              <FormReview
                items={answered(requirement.items)} data={pageData} groups={focused ? othersGroups : groups}
                myGroups={myGroups} shown={pageShown} onEvidence={openEvidence} actions={pageActions}
                selected={paneMark.id} onSelect={id => {
                  setPaneMark(currentMark => ({ ...currentMark, id }));
                  follow(id);
                }}
                heading={heading} notesSlot={isActive ? notesSlot : null} formLabel={requirement.label}
              />
            </SearchContext.Provider>
          </Box>
        )}
        {document && (
          <Box sx={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>
            <DocumentReview
              key={requirement.name}
              document={document} data={pageData} shown={pageShown} selected={paneMark.id}
              compareWith={comparing(document)} hideChanges={!showChanges}
              onHideChanges={hidden => setShowChanges(!hidden)}
              onChangeCount={count => countChanges(document.uuid, count)}
              onSelect={id => {
                setPaneMark(currentMark => ({ ...currentMark, id }));
                follow(id);
              }} page={paneMark.page}
              actions={pageActions} questionLabel={questionLabel}
              search={isActive ? { query: terms, ...documentStep } : QUIET_SEARCH}
              onSearch={isActive ? setDocumentFound : ignore}
              notesWidth={notesWidth} onNotesWidth={setNotesWidth} heading={heading ?? <Box />}
              onShowAnswer={openAnswer}
              notesSlot={isActive ? notesSlot : null}
              writeSlot={bottom ? writeSlots[pane] : undefined}
              onClose={() => close(pane)}
            />
          </Box>
        )}
      </Box>
    );
  };
  const title = current && withChangesSwitch(current,
    <Typography variant="h6" component="h2" sx={{ mb: 0.5 }}>{current.label}</Typography>);
  const shownDocument = current ? seen?.documents.get(current.name) : undefined;

  // A contents entry: navigation, so it looks it, names in the link colour, a tint under the pointer, and the one
  // being read marked by a bar down its side, on the white of the page it is the tab of
  const entrySx = (dimmed: boolean) => (theme: Theme) => ({
    borderRadius: 0, alignItems: "flex-start",
    // Each entry set apart from the next by a rule under it
    py: 1, mb: 0, boxShadow: `inset 0 -1px 0 ${(theme.vars ?? theme).palette.divider}`,
    borderInlineStart: "3px solid", borderColor: "transparent",
    "& .requirement-name": { color: "primary.main", fontWeight: "fontWeightMedium" },
    ...dimmed ? { opacity: 0.5 } : {},
    "&:hover": { bgcolor: theme.alpha(theme.palette.background.paper, 0.5) },
    "& .requirement-go": { opacity: 0, transition: theme.transitions.create("opacity") },
    "&:hover .requirement-go, &:focus-visible .requirement-go": { opacity: 1 },
    "&&.Mui-selected, &&.Mui-selected:hover": {
      bgcolor: "background.paper", borderColor: "primary.main",
    },
    "&.Mui-selected .requirement-name": { fontWeight: "fontWeightBold" },
  });
  // The entry being read, pinned to the edge of the list it scrolled out by, apart from the list by a shadow and an
  // arrow pointing where it is, which a click scrolls back to; it stays the tab of the page
  const pinnedEntry = current && pinned && (
    <Box sx={theme => ({ position: "relative", zIndex: 1, boxShadow: theme.shadows[3] })}>
      <ListItemButton
        selected aria-label={`${current.label}, scroll the contents to it`}
        onClick={() => readEntry?.scrollIntoView({ block: "nearest", behavior: "smooth" })}
        sx={theme => ({ ...entrySx(false)(theme), mb: 0, alignItems: "center" })}
      >
        <ListItemText primary={<span className="requirement-name">{current.label}</span>}
          slotProps={{ primary: { variant: "body2" } }} sx={{ my: 0.25 }} />
        {pinned === "above"
          ? <ArrowUpwardOutlined fontSize="small" sx={{ color: "text.secondary", ml: 1 }} />
          : <ArrowDownwardOutlined fontSize="small" sx={{ color: "text.secondary", ml: 1 }} />}
      </ListItemButton>
    </Box>
  );
  // Which kinds of notes are shown, for the whole submission: the contents' counts, what the page marks, and the notes
  // listed. Each kind as the contents count it, on its tint with its icon, when shown; plain when not. Last, whether
  // what is done with is shown too, the same way, on a neutral tint, having no kind's.
  const toggleSx = (on: boolean, mark?: MarkKind) => (theme: Theme) => ({ display: "inline-flex", alignItems: "center",
    gap: 0.5, px: 0.75, py: 0.25, borderRadius: 1, typography: "caption", lineHeight: 1.6,
    ...!on ? { color: "text.secondary" } : mark ? { ...getPassageCardStyle(theme, mark, 0.5),
      color: `${mark}.main`, fontWeight: "fontWeightMedium" }
      : { bgcolor: "action.selected", color: "text.primary", fontWeight: "fontWeightMedium" },
    "&:hover": on && mark ? { backgroundImage: getPassageCardStyle(theme, mark, 0.8).backgroundImage }
      : { bgcolor: on ? "action.focus" : "action.hover" },
    "&.Mui-focusVisible": { outline: 2, outlineColor: "primary.main" } });
  const noteFilters = (
    <Box role="group" aria-label="Which notes to show, in the whole submission"
      sx={{ display: "grid", gridTemplateColumns: "auto minmax(0, 1fr)", columnGap: 1, rowGap: 0.75,
        alignItems: "baseline", flexShrink: 0,
        ...narrow ? {} : { px: 1.5, pt: 1.25, pb: 1, borderBottom: 1, borderColor: "divider" } }}>
      {FILTER_GROUPS.map(group => (
        <Fragment key={group.label}>
          <Typography variant="caption" sx={{ color: "text.secondary", whiteSpace: "nowrap" }}>{group.label}</Typography>
          <Stack direction="row" sx={{ flexWrap: "wrap", gap: 0.5 }}>
            {group.kinds.filter(kind => !ALWAYS.includes(kind)).map(kind => {
              const info = kindInfo(kind);
              const on = shown.has(kind);
              return (
                <Tooltip key={kind} describeChild disableInteractive title={`${info.label}. ${COUNTED_ACROSS}`}>
                  <ButtonBase aria-pressed={on} onClick={() => toggleKind(kind)} sx={toggleSx(on, info.mark)}>
                    <NoteKindIcon kind={kind} sx={{ fontSize: "0.9rem", ...on ? {} : { opacity: 0.6 } }} />
                    {SHORT_LABELS[kind] ?? info.label} ({kindCount(kind)})
                  </ButtonBase>
                </Tooltip>
              );
            })}
          </Stack>
        </Fragment>
      ))}
    </Box>
  );
  // The notes done with on the part worked in, hidden unless the reviewer asks: said at the foot of its notes, where
  // they would be, and shown or hidden from there
  const resolvedHere = current ? notesOf(current, true).filter(note => note.done && showing.has(note.kind)).length : 0;
  const resolvedLine = (resolvedHere > 0 || showDone) && (
    <Typography variant="caption" sx={{ display: "block", color: "text.secondary", flexShrink: 0,
      ...narrow ? {} : { px: 1.5, py: 1, borderTop: 1, borderColor: "divider" } }}>
      {showDone ? "Showing notes resolved, dismissed or addressed by an issue. "
        : `${resolvedHere === 1 ? "1 resolved note" : `${resolvedHere} resolved notes`} hidden. `}
      <Link component="button" variant="caption" sx={{ verticalAlign: "baseline" }} onClick={() => setShowDone(!showDone)}>
        {showDone ? "Hide" : "Show"}
      </Link>
    </Typography>
  );

  const reviewPanel = (
    <GroupLabels.Provider value={groupLabels}>
      <ReviewerTopics.Provider value={topicsWritten}>
        <ReviewPanel
          risks={risks} unfiled={unfiled} issueCount={myIssues.length} group={myGroupName}
          others={othersRaised} about={about} reveal={revealed} storedAs={`iap.review.entry.${path}`}
          listFolded={listFolded} onListFolded={setListFolded} onFocusing={setFocusing}
          doing={{ ...doing, decision: reviewActions }}
          full={reviewFull} onFull={spreadReview} headed={reviewFull || narrow}
          letter={letterInput.template ? { draft: letter, send: () => setDialog("changes"),
            sendBlocked: reviewActions.blocked ?? (waiting ? undefined : "Your review is not waiting on a decision now") }
            : undefined}
        />
      </ReviewerTopics.Provider>
    </GroupLabels.Provider>
  );
  // The panel beside the page: the notes on what is read, saying how many, or the review. On a narrow
  // screen, where the notes go along the page, the choice is between the page and the review
  const tabCount = (count: string) => <Box component="span" sx={{ ml: 0.75, color: "text.secondary" }}>{count}</Box>;
  const foldButton = (side: "left" | "right", onClick: () => void, label: string,
    place: Record<string, number | string>) => <FoldButton side={side} label={label} place={place} onClick={onClick} />;
  // Halfway between the panel's tint and the page's grey, for the tab not shown
  const halfwayTint = (theme: Theme) => {
    const { background } = (theme.vars ?? theme).palette;
    return `color-mix(in srgb, ${background.tinted}, ${background.default})`;
  };
  const sideTabs = (
    <Tabs
      value={sideTab} onChange={(_event, value: SideTab) => setSideTab(value)} variant="fullWidth"
      aria-label="What to show beside the page"
      // As folder tabs: the one shown on the tint of what it heads, running into it, its name bold under a bar along
      // its top; the other halfway between that tint and the page's grey, set back, ruled off from what is under it
      sx={{ minHeight: 40, flexShrink: 0, bgcolor: halfwayTint,
        "& .MuiTabs-indicator": { top: 0, bottom: "auto", height: 3 },
        "& .MuiTab-root": { minHeight: 40, py: 0.5, textTransform: "none", typography: "subtitle2",
          color: "text.secondary", borderBottom: 1, borderColor: "divider" },
        // Set back: shaded where the panel and the tab shown meet it, as if they lay over it
        "& .MuiTab-root:not(.Mui-selected)": { "--tab-shade": theme => theme.alpha(theme.palette.common.black, 0.16) },
        "& .MuiTab-root:not(.Mui-selected):first-of-type": {
          boxShadow: "inset 0 -5px 5px -5px var(--tab-shade), inset -5px 0 5px -5px var(--tab-shade)" },
        "& .MuiTab-root:not(.Mui-selected):last-of-type": {
          boxShadow: "inset 0 -5px 5px -5px var(--tab-shade), inset 5px 0 5px -5px var(--tab-shade)" },
        "& .MuiTab-root.Mui-selected": { bgcolor: narrow ? "background.paper" : "background.tinted",
          color: "primary.main", fontWeight: "fontWeightBold", borderBottomColor: "transparent" } }}
    >
      <Tab value="notes" label={narrow ? "Submission" : <span>
        Notes{current && tabCount(String(notesOf(current).filter(note => showing.has(note.kind)).length))}
      </span>} />
      <Tab value="review" label="Review" />
    </Tabs>
  );

  // PRETOTYPE: what changed in a part since the submission was sent back, as its contents entry says it
  const changeChip = (change: PartChange | undefined) => change && (
    <Tooltip title={change.said} placement="right">
      <Box component="span" sx={{ display: "inline-flex", alignItems: "center", gap: 0.25, px: 0.5,
        lineHeight: 1.6, borderRadius: 1, bgcolor: "action.selected", color: "text.secondary" }}>
        <DifferenceOutlined sx={{ fontSize: "0.9rem" }} />{change.shown}
      </Box>
    </Tooltip>
  );

  const rail = narrow ? (
    <TextField
      select fullWidth size="small" label="Requirement" value={current?.name ?? ""}
      onChange={event => go(event.target.value)}
    >
      {listed.map(requirement => (
        <MenuItem key={requirement.name} value={requirement.name}>
          {requirement.label} ({terms ? `${hitCount(requirement) ?? "…"} found`
            : notesOf(requirement).filter(note => showing.has(note.kind)).length})
        </MenuItem>
      ))}
    </TextField>
  ) : (
    <List dense disablePadding aria-label="Requirements">
      {listed.map(requirement => {
        const counted = notesOf(requirement).filter(note => showing.has(note.kind));
        // While searching, how many times it has the terms instead, and greyed out when none
        const found = hitCount(requirement);
        return (
          // With a second part open, both are marked, the one being worked in a tab of the page; on a wide screen, any
          // other offers to open it below the one shown, to compare them
          <Box key={requirement.name} sx={{ position: "relative", "&:hover .open-below, & .open-below:focus-visible": {
            opacity: 1 } }}>
            <ListItemButton
              ref={requirement.name === current?.name ? setReadEntry : undefined}
              selected={requirement.name === top?.name || requirement.name === bottom?.name}
              onClick={() => go(requirement.name)}
              sx={entrySx(found === 0)}
            >
              <ListItemText
                primary={
                // What a requirement asks for: under the one being read, on hover over the name of the others
                  <Tooltip
                    placement="right" describeChild disableInteractive
                    title={requirement.name === current?.name ? "" : requirement.description ?? ""}
                  >
                    <span className="requirement-name">{requirement.label}</span>
                  </Tooltip>
                }
                secondary={<>
                  {requirement.name === current?.name && requirement.description}
                  {/* How many notes of each kind shown, on a line of their own so the name keeps the row's width;
                      PRETOTYPE: led by what changed since the submission was last sent back */}
                  {!terms && (counted.length > 0 || changeOf(requirement)) && (
                    <Box component="span" sx={{ display: "flex", flexWrap: "wrap", gap: 0.5, mt: 0.5 }}>
                      {/* A document's, how many changes once they are counted */}
                      {changeOf(requirement)?.document ? (
                        <DocumentChangeCount {...changeOf(requirement)?.document as { older: string; newer: string }}
                          shown={changeCounts[changeOf(requirement)?.document?.uuid ?? ""]}>
                          {count => count === undefined ? changeChip(changeOf(requirement))
                            : changeChip({ shown: String(count),
                              said: `${count === 1 ? "1 change" : `${count} changes`} since the version sent back` })}
                        </DocumentChangeCount>
                      ) : changeChip(changeOf(requirement))}
                      {counted.length > 0 && (
                        <Tooltip title={countText(counted)} placement="right">
                          <Box component="span" sx={{ display: "inline-flex", flexWrap: "wrap", gap: 0.5 }}>
                            {NOTE_KINDS.map(({ kind }) => {
                              const many = counted.filter(note => note.kind === kind).length;
                              return many > 0 && (
                              // Each on its kind's tint, as its notes are, to stand out on the contents' own
                                <KindCount key={kind} kind={kind} count={many} />
                              );
                            })}
                          </Box>
                        </Tooltip>
                      )}
                    </Box>
                  )}
                  {!focused && (
                    <Box component="span" sx={{ display: "block", mt: 0.25 }}>
                      <GroupChips groups={groupsOf(requirement)} mine={myGroups} />
                    </Box>
                  )}
                </>}
                slotProps={{ primary: { variant: "body2" }, secondary: { variant: "caption", component: "div" } }}
                sx={{ my: 0.25 }}
              />
              <Stack direction="row" sx={{ alignItems: "center", ml: 1, flexShrink: 0 }}>
                {terms && found !== undefined && found > 0 && (
                  <Tooltip title={found === 1 ? "Found once" : `Found ${found} times`} placement="right">
                    <Stack direction="row" sx={{ alignItems: "center", gap: 0.5, color: "text.secondary" }}>
                      <SearchOutlined sx={{ fontSize: "0.9rem" }} />
                      <Typography variant="caption">{found}</Typography>
                    </Stack>
                  </Tooltip>
                )}
                {bottom && (requirement.name === top?.name || requirement.name === bottom.name) && (
                  <Typography variant="caption" sx={{ color: "text.secondary" }}>
                    {requirement.name === top?.name ? "Top" : "Below"}
                  </Typography>
                )}
                {/* Where the entry leads, once the pointer is on it */}
                <ChevronRightOutlined
                  className="requirement-go" fontSize="small"
                  sx={{ color: "primary.main", ml: 0.25, visibility: requirement.name === current?.name ? "hidden" : undefined }}
                />
              </Stack>
            </ListItemButton>
            {requirement.name !== top?.name && requirement.name !== bottom?.name && (
              <Tooltip title="Open below in split view" placement="right" disableInteractive>
                <IconButton
                  className="open-below" size="small" aria-label={`Open ${requirement.label} below in split view`}
                  onClick={() => openBelow(requirement.name)}
                  // Left of where the entry's caret shows on hover, clear of it
                  sx={{ position: "absolute", top: 4, right: 44, opacity: 0, bgcolor: "background.paper",
                    "&:hover": { bgcolor: "background.paper" } }}
                >
                  <SplitscreenOutlined fontSize="small" />
                </IconButton>
              </Tooltip>
            )}
          </Box>
        );
      })}
    </List>
  );

  return (
    <Stack spacing={1.5}>
      {/* The app bar names the submission, but a phone's has room for its identifier only, and a short screen folds
          the bar away: those repeat the title and the time left */}
      <Box sx={theme => ({ display: { sm: "none" },
        [`@media (max-height: ${theme.iapShell?.frameTop?.collapseHeight ?? 500}px)`]: { display: "block" } })}>
        <Typography variant="h6" aria-hidden sx={{ lineHeight: 1.3 }}>{text(submission.title)}</Typography>
        <Countdown due={submission.reviewDue} pausedAt={submission.reviewPausedAt} />
      </Box>
      <Stack direction={{ xs: "column", sm: "row" }} sx={{ gap: 1, alignItems: { sm: "center" }, display: { md: "none" } }}>
        <Box>{search(false)}</Box>
      </Stack>
      {/* On a wide screen, the workspace fills the window under the app bar, the panels side by side, each as tall as
          the others, scrolling on its own; on a narrow one, the page or the review, chosen above them, with which notes
          are shown along the page */}
      <Stack sx={{ gap: 1.5, height: { md: PANEL_HEIGHT } }}>
        {/* PRETOTYPE: the review of another group than the one that sent the submission back started on the version
          before; a newer one came in since. Said only, for now: the review is of the latest version */}
        {!viewed && newerSince && (
          <Alert severity="info" icon={<HistoryOutlined />} sx={{ py: 0, alignItems: "center", flexShrink: 0 }}>
            {`A newer version came in on ${shortDate(newerSince.at)}, after ${groupLabels.get(newerSince.group)
              ?? "a reviewer group"} sent it back on ${shortDate(newerSince.sentBack)}. Your review started on the `
              + "earlier version."}
          </Alert>
        )}
        {/* PRETOTYPE: an older version read instead of the latest says so, and leads back to it */}
        {viewed && (
          <Alert severity="info" icon={<HistoryOutlined />} sx={{ py: 0, alignItems: "center", flexShrink: 0 }}
            action={<Button color="inherit" size="small" onClick={() => setViewing(undefined)}>
              Back to the latest version
            </Button>}>
            {`You're reading the ${versionName(viewed.at).replace("Version", "version")}, as ${groupLabels.get(viewed.group)
              ?? "a reviewer group"} sent it back. Nothing can be written in it.`}
          </Alert>
        )}
        {narrow && sideTabs}
        {narrow && sideTab === "review" && <Paper sx={{ display: "flex", flexDirection: "column" }}>{reviewPanel}</Paper>}
        {narrow && sideTab === "notes" && noteFilters}
        {narrow && sideTab === "notes" && resolvedLine}
        <Stack direction={{ xs: "column", md: "row" }}
          sx={{ gap: 1.5, alignItems: { xs: "flex-start", md: "stretch" }, flex: { md: 1 }, minHeight: { md: 0 },
            position: "relative", ...narrow && sideTab === "review" ? { display: "none" } : {} }}>
          {/* Under the review over the whole workspace, the submission is folded to the left, as the contents fold, and
            unfolds back beside it */}
          {full && !narrow && (
            // On the page's own grey, covering the contents and the page as the review beside it does
            <Box sx={{ position: "absolute", inset: 0, zIndex: 2, bgcolor: "background.default" }}>
              <Paper sx={{ height: "100%", width: FOLDED_WIDTH, py: 1, display: "flex", flexDirection: "column",
                alignItems: "center", gap: 1 }}>
                <Tooltip title="Back beside the page" placement="right" disableInteractive>
                  <IconButton size="small" aria-label="Show the submission beside the review"
                    onClick={() => spreadReview(false)}>
                    <ChevronRightOutlined />
                  </IconButton>
                </Tooltip>
                <Typography variant="caption" sx={{ writingMode: "vertical-rl", transform: "rotate(180deg)",
                  color: "text.secondary", whiteSpace: "nowrap" }}>
                  Submission
                </Typography>
              </Paper>
            </Box>
          )}
          {railOpen || narrow ? (
          // Part of the frame, tinted like the app bar; the requirement being read is a tab of the white page beside it
            <Paper sx={{ py: 0.5, width: { xs: "100%", md: 240 }, flexShrink: 0,
              ...narrow ? {} : { display: "flex", flexDirection: "column" },
              overflow: "hidden", bgcolor: { md: "background.tinted" }, boxShadow: { md: "none" },
              mr: { md: -1.5 }, borderTopRightRadius: { md: 0 }, borderBottomRightRadius: { md: 0 },
              borderRight: { md: "none" } }}>
              {/* As tall as when it held a button, its title level with the page's */}
              <Stack direction="row" sx={{ alignItems: "center", minHeight: 34, pl: 1.5, pr: 0.5, pt: 0.75, pb: 0.5,
                boxSizing: "content-box" }}>
                <Typography variant="panelTitle" sx={{ flex: 1 }}>Submission contents</Typography>
              </Stack>
              <ToggleButtonGroup
                exclusive fullWidth size="small" value={focused ? "focused" : "all"} aria-label="What to show"
                onChange={(_event, value: string | null) => value && setFocused(value === "focused")}
                sx={{ px: 1.5, pb: 1, "& .MuiToggleButton-root": { py: 0.25, textTransform: "none" } }}
              >
                <Tooltip describeChild disableInteractive title={myGroupLabel ? `Only what the ${groupShortName(myGroupLabel)} review covers`
                  : "Only what your review covers"}>
                  <ToggleButton value="focused">Focused</ToggleButton>
                </Tooltip>
                <Tooltip describeChild disableInteractive title="The whole submission">
                  <ToggleButton value="all">All</ToggleButton>
                </Tooltip>
              </ToggleButtonGroup>
              {!narrow && pinned === "above" && pinnedEntry}
              {narrow ? rail : <PanelBody follow={current?.name} noScrollbar>{rail}</PanelBody>}
              {!narrow && pinned === "below" && pinnedEntry}
            </Paper>
          ) : (
          // Folded, it still says what it is
            <Paper sx={{ width: FOLDED_WIDTH, py: 1, flexShrink: 0, display: "flex",
              flexDirection: "column", alignItems: "center", gap: 1 }}>
              <Tooltip title="Show the contents" placement="right" disableInteractive>
                <IconButton size="small" aria-label="Show the submission contents" onClick={() => setRailOpen(true)}>
                  <ChevronRightOutlined />
                </IconButton>
              </Tooltip>
              <Typography
                variant="caption"
                sx={{ writingMode: "vertical-rl", transform: "rotate(180deg)", color: "text.secondary", whiteSpace: "nowrap" }}
              >
                Submission contents
              </Typography>
            </Paper>
          )}
          {/* As tall as the panels, so the contents' tab always meets it; its notes go down its right edge, so it keeps no
            room there or under them. A form gets more room around its questions than a document, whose viewer brings
            its own */}
          <GroupLabels.Provider value={groupLabels}>
            <ReviewerTopics.Provider value={topicsWritten}>
              {/* The page, with tags on its edges folding the panels beside it away, or bringing them back */}
              {/* Not where the review spread over the whole workspace is laid, which covers the contents too */}
              <Box sx={{ position: full ? "static" : "relative", flex: 1, minWidth: 0, width: "100%", display: "flex" }}>
                {!narrow && railOpen && !full && foldButton("left", () => setRailOpen(false), "Fold the contents away",
                  { left: -FOLD_SIZE / 2 })}
                <Paper sx={{ px: 2, py: 1.5, flex: 1, minWidth: 0, width: "100%",
                  ...narrow ? {} : { p: 0, height: "100%", display: "flex", flexDirection: "column",
                    overflow: "hidden" },
                  ...railOpen && !narrow ? { borderTopLeftRadius: 0, borderBottomLeftRadius: 0, borderLeft: "none" } : {} }}>
                  {narrow ? current && (
                    <>
                      {isFormRequirement(current) && (
                        <Box sx={{ flex: { md: 1 }, minHeight: { md: 0 } }}>
                          <SearchContext.Provider value={searchContext}>
                            <FormReview
                              items={answered(current.items)} data={viewData ?? data}
                              groups={focused ? othersGroups : groups}
                              myGroups={myGroups} shown={showing}
                              onEvidence={openEvidence} actions={pageActions}
                              notesWidth={notesWidth} onNotesWidth={setNotesWidth}
                              selected={mark.id} onSelect={id => setMark(currentMark => ({ ...currentMark, id }))}
                              heading={title}
                              formLabel={current.label}
                            />
                          </SearchContext.Provider>
                        </Box>
                      )}
                      {isDocumentRequirement(current) && shownDocument && (
                        <Box sx={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>
                          {/* Drawn again on coming back from the review, as it cannot lay out its pages while hidden */}
                          <DocumentReview
                            key={`${current.name} ${sideTab}`}
                            document={shownDocument} data={viewData ?? data} shown={showing} selected={mark.id}
                            compareWith={comparing(shownDocument)} hideChanges={!showChanges}
                            onHideChanges={hidden => setShowChanges(!hidden)}
                            onChangeCount={count => countChanges(shownDocument.uuid, count)}
                            onSelect={id => setMark(currentMark => ({ ...currentMark, id }))} page={mark.page}
                            actions={pageActions} questionLabel={questionLabel}
                            search={{ query: terms, ...documentStep }} onSearch={setDocumentFound}
                            notesWidth={notesWidth} onNotesWidth={setNotesWidth}
                            heading={title}
                            onShowAnswer={openAnswer}
                            onClose={() => go(listed.find(isFormRequirement)?.name ?? current.name)}
                          />
                        </Box>
                      )}
                    </>
                  ) : (
                  // One part, or two one above the other, beside the notes of the one being worked in, listed down the
                  // right as tall as the panel
                    <Box ref={setWorkArea} sx={{ display: "flex", height: "100%", minHeight: 0 }}>
                      <Box ref={setPaneColumn} sx={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column" }}>
                        {top && part("top", top)}
                        {bottom && (
                          <RowSplit at={splitAt} onAt={setSplitAt} container={() => paneColumn} label="Resize the parts" />
                        )}
                        {bottom && part("bottom", bottom)}
                      </Box>
                      {sideShown && (
                        <SplitHandle
                          width={notesShown} onWidth={setNotesWidth} container={() => workArea} min={NOTES_LEAST}
                          reserve={PAGE_LEAST} label="Resize the notes" onReset={() => setNotesWidth(NOTES_WIDTH)}
                        />
                      )}
                      {/* Over the whole workspace, the review covers the contents and the page, which stay as they were
                        under it, to come back to */}
                      {/* Folded away, it stays, hidden, the notes listed into it by the page staying in step */}
                      <Box sx={{ width: notesShown, flexShrink: 0, height: "100%", display: sideShown ? "flex" : "none",
                        flexDirection: "column", bgcolor: "background.tinted", position: "relative", ...full ? { position: "absolute",
                          top: 0, right: 0, bottom: 0, left: theme => `calc(${FOLDED_WIDTH}px + ${theme.spacing(1.5)})`,
                          width: "auto", zIndex: 2, borderRadius: 1, border: 1, borderColor: "divider",
                          overflow: "hidden" } : {} }}>
                        {/* Its tabs, after the tag folding it away, on its inner edge; none while the review's
                            contents are folded away, its page saying where it is */}
                        {!full && !(focusing && sideTab === "review") && (
                          // With the review shown, the way to spread it over the whole workspace sits at the end of its
                          // tab, laid over it rather than in it, so the tab keeps its tint and its bar
                          <Box sx={{ pl: 0.5, bgcolor: halfwayTint, flexShrink: 0, position: "relative" }}>
                            {sideTabs}
                            {sideTab === "review" && (
                              <Box sx={{ position: "absolute", right: 4, top: 3, bottom: 0, display: "flex",
                                alignItems: "center" }}>
                                <Tooltip disableInteractive title="Open over the whole workspace" placement="left">
                                  <IconButton size="small" aria-label="Show the review over the whole workspace"
                                    onClick={() => spreadReview(true)}>
                                    <OpenInFullOutlined fontSize="small" />
                                  </IconButton>
                                </Tooltip>
                              </Box>
                            )}
                          </Box>
                        )}
                        {/* On the line of the handle beside it, half the handle's width before the panel */}
                        {!full && foldButton("right", () => setSideOpen(false), "Fold the notes and review away",
                          { left: -8 - FOLD_SIZE / 2 })}
                        {/* Kept while the review is shown, the notes listed into it by the page staying in step */}
                        <Box sx={{ flex: 1, minHeight: 0, flexDirection: "column",
                          display: sideTab === "notes" ? "flex" : "none" }}>
                          {noteFilters}
                          {/* Of two parts, which one's notes these are */}
                          {bottom && current && (
                            <Typography variant="body2" sx={{ px: 1.5, pt: 1.25, color: "text.secondary" }}>
                              Notes on <Box component="b" sx={{ color: "primary.main" }}>{current.label}</Box>
                            </Typography>
                          )}
                          <Box
                            ref={setNotesSlot}
                            sx={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column",
                              "&:empty::before": { content: "\"No notes here yet\"", color: "text.secondary",
                                typography: "body2", textAlign: "center", py: 2 } }}
                          />
                          {resolvedLine}
                          {wholeKinds.length > 0 && (
                            <WholeNotes notes={wholeNotes} kinds={wholeKinds} actions={pageActions} />
                          )}
                        </Box>
                        {sideTab === "review" && reviewPanel}
                      </Box>
                    </Box>
                  )}
                  {dialog === "sentLetter" && lastLetter && (
                    <SentLetter letter={lastLetter} onClose={() => setDialog(undefined)} />
                  )}
                  {dialog === "changes" && (
                    // What is sent, as the study team will read it, before it is
                    <ConfirmActionDialog
                      title={lettered ? "Request changes?" : "Send to the study team?"} confirmLabel="Send"
                      workingLabel="Sending…" width="md" onClose={() => setDialog(undefined)}
                      onConfirm={() => sendLetter(lettered ? letterText(letter) : emailText(email))}
                    >
                      <DialogContentText sx={{ mb: 2 }}>
                        {`The study team will get this ${lettered ? "letter" : "email"}. It pauses the clock until they `
                          + "answer, and your issues in it can't be changed after, only replied to."}
                      </DialogContentText>
                      {lettered ? <LetterPage letter={letter} /> : <EmailPreview email={email} />}
                    </ConfirmActionDialog>
                  )}
                  {dialog === "approve" && (
                    <ConfirmActionDialog
                      title={consulting ? `Finish your review of ${text(submission.identifier)}?`
                        : `Approve ${text(submission.identifier)}?`}
                      confirmLabel={consulting ? "Finish" : "Approve"} workingLabel={consulting ? "Finishing…" : "Approving…"}
                      onConfirm={() => decide("approved", suggestions.length > 0
                        ? approvalLetter({ ...letterInput, issues: suggestions }) : undefined)}
                      onClose={() => setDialog(undefined)}
                    >
                      {unconsidered + aiOpen + contradicted + unsentQuestions.length === 0 ? (
                        <DialogContentText sx={{ mb: 1 }}>
                          Every risk is marked as considered, and nothing the AI raised is still open.
                        </DialogContentText>
                      ) : (
                        <>
                          <DialogContentText>Still open in your review</DialogContentText>
                          <Box component="ul" sx={{ mt: 0.5, mb: 1, pl: 3, color: "text.secondary" }}>
                            {unconsidered > 0 && (
                              <li>{risks.length === 1 ? "Your risk isn't marked as considered."
                                : `${unconsidered} of your ${risks.length} risks ${unconsidered === 1 ? "isn't" : "aren't"} `
                                  + "marked as considered."}</li>
                            )}
                            {aiOpen > 0 && <li>{aiOpen === 1 ? "1 concern" : `${aiOpen} concerns`} the AI raised, not
                              made into issues or dismissed.</li>}
                            {contradicted > 0 && <li>{contradicted === 1 ? "1 AI-found mismatch"
                              : `${contradicted} AI-found mismatches`} between the answers and the protocol.</li>}
                            {unsentQuestions.length > 0 && <li>{unsentQuestions.length === 1 ? "1 question"
                              : `${unsentQuestions.length} questions`} you haven&apos;t sent to the study team.</li>}
                          </Box>
                        </>
                      )}
                      {suggestions.length > 0 && (
                        <DialogContentText sx={{ mb: 1 }}>
                          Your {suggestions.length === 1 ? "suggestion goes" : `${suggestions.length} suggestions go`} to
                          the study team with the approval.
                        </DialogContentText>
                      )}
                      <DialogContentText>
                        {consulting ? "Finishing your review cannot be taken back." : "An approval cannot be taken back."}
                      </DialogContentText>
                    </ConfirmActionDialog>
                  )}
                  {dialog === "board" && (
                    <EscalateDialog
                      open title={text(submission.title)} identifier={text(submission.identifier)}
                      pi={text(submission.pi)}
                      summary={data.answers.get("study/summary")?.values.at(0) ?? ""}
                      parts={listed.map(one => ({ name: one.name, label: one.label }))}
                      issues={openIssues} about={about}
                      onClose={() => setDialog(undefined)}
                    />
                  )}

                </Paper>
              </Box>
              {!sideShown && (
                // Folded, it still says what it holds, mirroring the contents on the other side: a button bringing it
                // back, and its tabs, read downwards, each opening it on that tab
                <Paper sx={{ width: FOLDED_WIDTH, py: 1, flexShrink: 0, display: "flex", flexDirection: "column",
                  alignItems: "center", gap: 1 }}>
                  <Tooltip title="Show the notes and the review" placement="left" disableInteractive>
                    <IconButton size="small" aria-label="Show the notes and the review" onClick={() => setSideOpen(true)}>
                      <ChevronLeftOutlined />
                    </IconButton>
                  </Tooltip>
                  {([ [ "notes", "Notes" ], [ "review", "Review" ] ] as const).map(([ tab, name ]) => (
                    <ButtonBase key={tab} aria-label={`Show the ${name.toLowerCase()}`}
                      onClick={() => {
                        setSideTab(tab);
                        setSideOpen(true);
                      }}
                      sx={{ writingMode: "vertical-rl", typography: "caption", whiteSpace: "nowrap", py: 1, px: 0.5,
                        borderRadius: 1, color: sideTab === tab ? "primary.main" : "text.secondary",
                        fontWeight: sideTab === tab ? "fontWeightBold" : "fontWeightRegular",
                        "&:hover": { bgcolor: "action.hover" } }}>
                      {name}
                    </ButtonBase>
                  ))}
                </Paper>
              )}
            </ReviewerTopics.Provider>
          </GroupLabels.Provider>
        </Stack>
      </Stack>
    </Stack>
  );
}

