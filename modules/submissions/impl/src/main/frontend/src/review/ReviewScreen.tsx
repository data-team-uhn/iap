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

import { useCallback, useEffect, useMemo, useState } from "react";

import ChatBubbleOutlineOutlined from "@mui/icons-material/ChatBubbleOutlineOutlined";
import CheckCircleOutlined from "@mui/icons-material/CheckCircleOutlined";
import ChevronLeftOutlined from "@mui/icons-material/ChevronLeftOutlined";
import ChevronRightOutlined from "@mui/icons-material/ChevronRightOutlined";
import CircleOutlined from "@mui/icons-material/CircleOutlined";
import SearchOutlined from "@mui/icons-material/SearchOutlined";
import {
  Box,
  Button,
  Chip,
  IconButton,
  List,
  ListItemButton,
  ListItemText,
  MenuItem,
  Paper,
  Stack,
  Tooltip,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
  useMediaQuery,
  useTheme,
} from "@mui/material";

import LoadError from "@iap/frontend-commons/components/LoadError";
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import { usePublishedPageContext } from "@iap/frontend-commons/pageContext";
import type { DocumentSearch } from "@iap/frontend-commons/pdfViewer/pdfViewerModel";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
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
import { kindChip, ReviewerTopics, WRITTEN } from "./Composer";
import Countdown from "./Countdown";
import DocumentReview from "./DocumentReview";
import EscalateDialog from "./EscalateDialog";
import FormReview, { answerText, GroupChips, type ReviewActions } from "./FormReview";
import IssuesPanel from "./IssuesPanel";
import LetterDialog from "./LetterDialog";
import { GroupLabels, usePersonName } from "./people";
import { draftLetter } from "./rebLetter";
import { writeComment } from "./reviewApi";
import { groupShortName, isMine, NOTE_KINDS, notesAbout, parseGroups, parseReview, parseTopics, type NoteKind,
  type ReviewData, type ReviewGroup, type ReviewNote, type Topic } from "./reviewData";
import { SearchContext } from "./SearchHighlight";
import { documentText, formMatches, pageCounts } from "./searchSubmission";
import { PANEL_HEIGHT, PanelBody, panelPlacement } from "./sidePanel";
import SubmissionSearch from "./SubmissionSearch";
import { useCurrentUser } from "./useCurrentUser";

type Node = Record<string, unknown>;

// Renamed when the default changed, so every reviewer starts from it once
const KINDS_KEY = "iap.review.shownNotes";
const CONTENTS_OPEN_KEY = "iap.review.contentsOpen";
const ISSUES_OPEN_KEY = "iap.review.issuesOpen";
const FOCUSED_KEY = "iap.review.focused";

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

// Every kind but the extracted answers, until the reviewer chooses: those agreeing with the answer add little, and
// those that do not are said under the answer anyway
const DEFAULT_KINDS: NoteKind[] = NOTE_KINDS.map(info => info.kind).filter(kind => kind !== "evidence");

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

function useMyGroups(me: string | undefined): string[] {
  const doFetch = useAuthenticatedFetch();
  const [ groups, setGroups ] = useState<string[]>([]);
  useEffect(() => {
    if (!me) {
      return;
    }
    void doFetch(`/system/userManager/user/${encodeURIComponent(me)}.json`)
      .then(response => (response.ok ? response.json() : {}) as Promise<{ memberOf?: string[] }>)
      .then(user => setGroups((user.memberOf ?? []).map(group => group.split("/").at(-1) ?? group)));
  }, [ doFetch, me ]);
  return groups;
}

interface Loaded {
  submission: Node;
  form: SubmissionForm;
  approvals: Node[];
  // The forms every reviewer reads, whichever groups also draw on them
  everyone: Set<string>;
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
  return { submission, form: form as unknown as SubmissionForm, approvals, everyone };
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
  const [ shown, setShown ] = useState(storedKinds);
  const [ mark, setMark ] = useState<{ id?: string; page?: number }>({});
  const [ dialog, setDialog ] = useState<"letter" | "board">();
  // Whether the issues are shown beside the requirement; until the reviewer says, open beside a form and folded
  // beside a document, which needs the width
  const [ issuesOpen, setIssuesOpen ] = useStoredFlag(ISSUES_OPEN_KEY);
  const [ railOpen = true, setRailOpen ] = useStoredFlag(CONTENTS_OPEN_KEY);
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
  const data: ReviewData | undefined = useMemo(() => loaded && me ? parseReview(loaded.submission, schema, me, topics)
    : undefined, [ loaded, schema, me, topics ]);
  // Each reviewer group as its approval calls it for short, to say whose another reviewer's issue is
  const groupLabels = useMemo(() => new Map((loaded?.approvals ?? []).map(approval =>
    [ text(approval.approverGroup), groupShortName(text(approval.label)) ] as const)), [ loaded ]);
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
  const current = trimmed.find(one => one.name === selected) ?? trimmed.find(mine) ?? trimmed.at(0);
  const listed = focused ? trimmed.filter(one => one === current || mine(one)) : trimmed;

  // The reviewer groups a requirement concerns: those any of its questions, or the document, matters to
  const groupsOf = (requirement: Requirement): ReviewGroup[] | undefined => {
    const parts = isFormRequirement(requirement) ? questions(requirement.items).map(question => question.path)
      : [ requirement.name ];
    const found = new Map<string, ReviewGroup>();
    parts.forEach(part => groups.get(part)?.forEach(group => found.set(group.name, group)));
    return found.size > 0 ? [ ...found.values() ] : undefined;
  };

  const notesOf = useCallback((requirement: Requirement): ReviewNote[] => {
    if (!data) {
      return [];
    }
    if (isFormRequirement(requirement)) {
      const uuids = questions(requirement.items)
        .map(question => data.answers.get(question.path)?.uuid).filter((one): one is string => Boolean(one));
      return notesAbout(data.notes, uuids);
    }
    const document = data.documents.get(requirement.name);
    return document ? notesAbout(data.notes, [ document.uuid, document.version ]).filter(note => note.passage) : [];
  }, [ data ]);


  // The reviewer group the reviewer reviews this submission for
  const myGroup = (loaded?.approvals ?? []).map(approval => text(approval.approverGroup))
    .find(group => myGroups.includes(group));
  const actions: ReviewActions = useMemo(() => ({
    write: async fields => {
      await writeComment(doFetch, path, fields.kind === "issue" && myGroup ? { ...fields, group: myGroup } : fields);
      // A new note of a kind the reviewer hides shows that kind again, rather than vanishing as it is added
      const written = WRITTEN.find(kind => kind === fields.kind);
      if (written) {
        setShown(current => current.has(written) ? current : storeKinds(new Set([ ...current, written ])));
      }
      load();
    },
  }), [ doFetch, path, load, myGroup ]);

  const questionLabel = useCallback((question: string | undefined, extracted: string | undefined) => {
    const asked = requirements.filter(isFormRequirement).flatMap(one => questions(one.items))
      .find(one => one.path === question);
    return { text: asked?.text ?? "", answer: asked && extracted !== undefined
      ? answerText(asked, extracted.split(";").map(one => one.trim())) : undefined };
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

  const go = (requirement: string, focus?: { id?: string; page?: number; question?: string }) => {
    setSelected(requirement);
    setMark({ id: focus?.id, page: focus?.page });
    if (focus?.question) {
      const target = focus.question;
      setTimeout(() => document.getElementById(`question-${target}`)?.scrollIntoView({ behavior: "smooth" }), 50);
    } else {
      window.scrollTo({ top: 0 });
    }
  };

  const openEvidence = (note: ReviewNote) => {
    const document = [ ...data?.documents.entries() ?? [] ].find(([ , one ]) => one.version === note.passage?.source
      || one.uuid === note.passage?.source)?.[0] ?? "protocol";
    go(document, { id: note.id, page: note.passage?.page });
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

  // search beside them on wide screens
  const header = loaded?.submission;
  usePublishedPageContext(header && {
    title: text(header.title),
    short: text(header.identifier),
    details: text(header.pi),
    badges: <><TagChip tags={header.tags} category="lifecycle" /><TagChip tags={header.tags} category="priority" /></>,
    end: <Countdown due={header.reviewDue} pausedAt={header.reviewPausedAt} variant="caption" />,
    tools: search(true),
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

  // Where an issue was raised: the passage it quotes, or the answer or document it is about
  const openIssue = (note: ReviewNote) => {
    const source = note.passage?.source;
    const part = (source ? data?.partOf.get(source) : undefined)
      ?? note.subjects.map(subject => data?.partOf.get(subject)).find(Boolean);
    if (!part) {
      return;
    }
    if (source || data?.documents.has(part)) {
      go(part, { id: note.id, page: note.passage?.page });
    } else {
      go(part.split("/")[0], { question: part });
    }
  };

  // The reviewer's own review task: the open one their groups perform
  const myTask = (): string | undefined => {
    const instances = loaded?.submission["wf:instances"];
    for (const instance of Object.values(typeof instances === "object" && instances ? instances : {})) {
      for (const task of Object.values(typeof instance === "object" && instance ? instance as Node : {})) {
        const node = typeof task === "object" && task ? task as Node : undefined;
        const performers = Array.isArray(node?.performers) ? node.performers.map(String) : [];
        if (node?.["jcr:primaryType"] === "wf:TaskInstance" && [ "created", "active" ].includes(text(node.status))
          && performers.some(one => myGroups.includes(one))) {
          return text(node["@path"]);
        }
      }
    }
    return undefined;
  };
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
  const waiting = myTask() !== undefined;
  const reviewActions = {
    send: () => setDialog("letter"),
    approve: () => void decide("approved"),
    approveBlocked: !waiting ? "Your review is not waiting on a decision now"
      : myIssues.length > 0
        ? `Request changes for your ${myIssues.length === 1 ? "issue" : `${myIssues.length} issues`} first`
        : undefined,
    board: myGroups.includes("reb-reviewers") ? () => setDialog("board") : undefined,
  };
  const shownDocument = current ? data.documents.get(current.name) : undefined;

  const rail = narrow ? (
    <TextField
      select fullWidth size="small" label="Requirement" value={current?.name ?? ""}
      onChange={event => go(event.target.value)}
    >
      {listed.map(requirement => (
        <MenuItem key={requirement.name} value={requirement.name}>
          {requirement.label} ({terms ? `${hitCount(requirement) ?? "…"} found`
            : notesOf(requirement).filter(note => shown.has(note.kind)).length})
        </MenuItem>
      ))}
    </TextField>
  ) : (
    <List dense disablePadding aria-label="Requirements">
      {listed.map(requirement => {
        const count = notesOf(requirement).filter(note => shown.has(note.kind)).length;
        // While searching, how many times it has the terms instead, and greyed out when none
        const found = hitCount(requirement);
        return (
          <ListItemButton
            key={requirement.name}
            selected={requirement.name === current?.name} onClick={() => go(requirement.name)}
            // Navigation, so it looks it: names in the link colour, a tint under the pointer, and the one being read
            // marked by a bar down its side
            sx={theme => ({
              borderRadius: 0, py: 0.75, mb: 0.25, alignItems: "flex-start",
              borderInlineStart: "3px solid", borderColor: "transparent",
              "& .requirement-name": { color: "primary.main", fontWeight: "fontWeightMedium" },
              ...found === 0 ? { opacity: 0.5 } : {},
              "&:hover": { bgcolor: theme.alpha(theme.palette.background.paper, 0.5) },
              "& .requirement-go": { opacity: 0, transition: theme.transitions.create("opacity") },
              "&:hover .requirement-go, &:focus-visible .requirement-go": { opacity: 1 },
              "&&.Mui-selected, &&.Mui-selected:hover": {
                bgcolor: "background.paper", borderColor: "primary.main",
              },
              "&.Mui-selected .requirement-name": { fontWeight: "fontWeightBold" },
            })}
          >
            <ListItemText
              primary={
                // What a requirement asks for: under the one being read, on hover over the name of the others
                <Tooltip
                  placement="right" describeChild
                  title={requirement.name === current?.name ? "" : requirement.description ?? ""}
                >
                  <span className="requirement-name">{requirement.label}</span>
                </Tooltip>
              }
              secondary={<>
                {requirement.name === current?.name && requirement.description}
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
              {!terms && count > 0 && (
                <Tooltip title={countText(notesOf(requirement).filter(note => shown.has(note.kind)))} placement="right">
                  <Stack direction="row" sx={{ alignItems: "center", gap: 0.5, color: "text.secondary" }}>
                    <ChatBubbleOutlineOutlined sx={{ fontSize: "0.9rem" }} />
                    <Typography variant="caption">{count}</Typography>
                  </Stack>
                </Tooltip>
              )}
              {/* Where the entry leads, once the pointer is on it */}
              <ChevronRightOutlined
                className="requirement-go" fontSize="small"
                sx={{ color: "primary.main", ml: 0.25, visibility: requirement.name === current?.name ? "hidden" : undefined }}
              />
            </Stack>
          </ListItemButton>
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
        <Stack direction="row" spacing={1} sx={{ ml: { sm: "auto" }, flexWrap: "wrap" }}>
          {reviewActions.board && (
            <Button size="small" variant="text" onClick={reviewActions.board}>Escalate to the board</Button>
          )}
          <Button size="small" variant="outlined" onClick={reviewActions.send}>Request changes</Button>
          <Button size="small" variant="contained" disabled={Boolean(reviewActions.approveBlocked)}
            onClick={reviewActions.approve}>Approve</Button>
        </Stack>
      </Stack>
      <Stack direction={{ xs: "column", md: "row" }} sx={{ gap: 1.5, alignItems: "flex-start" }}>
        {/* The page runs at least as far down as the contents beside it, so whichever requirement is being read,
            its tab meets the page */}
        {railOpen || narrow ? (
          // Part of the frame, tinted like the app bar; the requirement being read is a tab of the white page beside it
          <Paper sx={{ py: 0.5, width: { xs: "100%", md: 240 }, flexShrink: 0,
            ...narrow ? {} : { ...panelPlacement, display: "flex", flexDirection: "column" },
            overflow: "hidden", bgcolor: { md: "background.tinted" }, boxShadow: { md: "none" },
            mr: { md: -1.5 }, borderTopRightRadius: { md: 0 }, borderBottomRightRadius: { md: 0 },
            borderRight: { md: "none" } }}>
            <Stack direction="row" sx={{ alignItems: "center", pl: 1.5, pr: 0.5, pt: 0.75, pb: 0.5 }}>
              <Typography variant="panelTitle" sx={{ flex: 1 }}>Submission contents</Typography>
              {!narrow && (
                <Tooltip title="Fold away">
                  <IconButton size="small" aria-label="Fold the contents away" onClick={() => setRailOpen(false)}>
                    <ChevronLeftOutlined />
                  </IconButton>
                </Tooltip>
              )}
            </Stack>
            <ToggleButtonGroup
              exclusive fullWidth size="small" value={focused ? "focused" : "all"} aria-label="What to show"
              onChange={(_event, value: string | null) => value && setFocused(value === "focused")}
              sx={{ px: 1.5, pb: 1, "& .MuiToggleButton-root": { py: 0.25, textTransform: "none" } }}
            >
              <Tooltip describeChild title={myGroupLabel ? `Only what the ${groupShortName(myGroupLabel)} review covers`
                : "Only what your review covers"}>
                <ToggleButton value="focused">Focused</ToggleButton>
              </Tooltip>
              <Tooltip describeChild title="The whole submission, including the parts that concern other reviewer groups">
                <ToggleButton value="all">All</ToggleButton>
              </Tooltip>
            </ToggleButtonGroup>
            {narrow ? rail : <PanelBody follow={current?.name} noScrollbar>{rail}</PanelBody>}
          </Paper>
        ) : (
          // Folded, it still says what it is
          <Paper sx={{ width: 48, py: 1, flexShrink: 0, ...panelPlacement, display: "flex",
            flexDirection: "column", alignItems: "center", gap: 1 }}>
            <Tooltip title="Show the contents" placement="right">
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
        {/* At least as tall as the panels, so the contents' tab always meets it; a document fills it exactly */}
        {/* A form gets more room around its questions than a document, whose viewer brings its own */}
        <GroupLabels.Provider value={groupLabels}>
          <ReviewerTopics.Provider value={reviewerTopics}>
            <Paper sx={{ px: 2, py: 1.5, flex: 1, minWidth: 0, width: "100%", alignSelf: { md: "stretch" },
              ...current && isFormRequirement(current) ? { px: { md: 4 }, py: { md: 3 } } : {},
              minHeight: { md: PANEL_HEIGHT },
              ...!narrow && current && isDocumentRequirement(current)
                ? { height: PANEL_HEIGHT, display: "flex", flexDirection: "column" } : {},
              ...railOpen && !narrow ? { borderTopLeftRadius: 0, borderBottomLeftRadius: 0, borderLeft: "none" } : {} }}>
              {current && (
                <>
                  <Stack direction="row" sx={{ alignItems: "center", columnGap: 2, rowGap: 1, flexWrap: "wrap", mb: 0.5 }}>
                    <Typography variant="h6" component="h2" sx={{ mr: "auto" }}>{current.label}</Typography>
                    {/* Over the notes they filter: on the right, where a document lists its notes */}
                    <Stack direction="row" sx={{ alignItems: "center", gap: 0.75, flexWrap: "wrap", justifyContent: "flex-end" }}
                      role="group" aria-label="Which notes to show">
                      <Typography variant="caption" sx={{ color: "text.secondary", mr: 0.25 }}>Show</Typography>
                      {NOTE_KINDS.map(info => (
                        <Chip
                          key={info.kind}
                          size="small"
                          label={info.label}
                          variant="outlined"
                          onClick={() => toggleKind(info.kind)}
                          aria-pressed={shown.has(info.kind)}
                          icon={shown.has(info.kind) ? <CheckCircleOutlined /> : <CircleOutlined />}
                          // Shown, softly tinted in its colour as a tag is; hidden, plain grey, so it reads as off
                          sx={theme => ({ "& .MuiChip-icon": { color: "inherit" },
                            ...shown.has(info.kind) ? kindChip(theme, info.mark)
                              : { borderColor: "divider", color: "text.disabled" } })}
                        />
                      ))}
                    </Stack>
                  </Stack>
                  {isFormRequirement(current) && (
                    <SearchContext.Provider value={searchContext}>
                      <FormReview
                        items={current.items} data={data} groups={focused ? othersGroups : groups}
                        myGroups={myGroups} shown={shown}
                        onEvidence={openEvidence} actions={actions}
                      />
                    </SearchContext.Provider>
                  )}
                  {isDocumentRequirement(current) && shownDocument && (
                    <Box sx={{ mt: 1, flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>
                      <DocumentReview
                        key={current.name}
                        document={shownDocument} data={data} shown={shown} selected={mark.id}
                        onSelect={id => setMark(currentMark => ({ ...currentMark, id }))} page={mark.page}
                        actions={actions} questionLabel={questionLabel}
                        search={{ query: terms, ...documentStep }} onSearch={setDocumentFound}
                        onClose={() => go(listed.find(isFormRequirement)?.name ?? current.name)}
                      />
                    </Box>
                  )}
                </>
              )}
              {dialog === "letter" && (
                <LetterDialog
                  open issueCount={myIssues.length}
                  draft={draftLetter({ title: text(submission.title), identifier: text(submission.identifier),
                    pi: text(submission.pi), about, issues: myIssues,
                    reviewer: myName, group: myGroupLabel.toLowerCase() })}
                  onSend={sendLetter} onClose={() => setDialog(undefined)}
                />
              )}
              {dialog === "board" && (
                <EscalateDialog
                  open title={text(submission.title)} identifier={text(submission.identifier)} pi={text(submission.pi)}
                  summary={data.answers.get("study/summary")?.values.at(0) ?? ""}
                  parts={listed.map(one => ({ name: one.name, label: one.label }))}
                  issues={myIssues} about={about}
                  onClose={() => setDialog(undefined)}
                />
              )}

            </Paper>
          </ReviewerTopics.Provider>
        </GroupLabels.Provider>
        <Box sx={{ display: { xs: "none", md: "flex" }, flexDirection: "column", ...panelPlacement, flexShrink: 0 }}>
          <IssuesPanel
            issues={myIssues} about={about}
            open={issuesOpen ?? !(current && isDocumentRequirement(current))}
            onToggle={() => setIssuesOpen(!(issuesOpen ?? !(current && isDocumentRequirement(current))))}
            onOpenIssue={openIssue}
            actions={reviewActions}
          />
        </Box>
      </Stack>
    </Stack>
  );
}

