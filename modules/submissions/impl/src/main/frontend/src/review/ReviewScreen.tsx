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

import ChevronLeftOutlined from "@mui/icons-material/ChevronLeftOutlined";
import ChevronRightOutlined from "@mui/icons-material/ChevronRightOutlined";
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
  Typography,
  useMediaQuery,
  useTheme,
} from "@mui/material";

import LoadError from "@iap/frontend-commons/components/LoadError";
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import { usePublishedPageContext } from "@iap/frontend-commons/pageContext";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import TagChip from "@iap/tags/TagChip";

import {
  type FormItem,
  type FormQuestion,
  type Requirement,
  type SubmissionForm,
  isDocumentRequirement,
  isFormRequirement,
  isQuestion,
} from "../submissionForm";
import Countdown from "./Countdown";
import DocumentReview from "./DocumentReview";
import EscalateDialog from "./EscalateDialog";
import FormReview, { answerText, GroupChips, type ReviewActions } from "./FormReview";
import IssuesPanel from "./IssuesPanel";
import LetterDialog from "./LetterDialog";
import { usePersonName } from "./people";
import { draftLetter } from "./rebLetter";
import { writeComment } from "./reviewApi";
import { isMine, NOTE_KINDS, notesAbout, parseGroups, parseReview, type NoteKind, type ReviewData,
  type ReviewGroup, type ReviewNote } from "./reviewData";
import { documentText, searchAnswers, searchPages, type Hit } from "./searchSubmission";
import SubmissionSearch from "./SubmissionSearch";
import { useCurrentUser } from "./useCurrentUser";

type Node = Record<string, unknown>;

const KINDS_KEY = "iap.review.kinds";

function storedKinds(): Set<NoteKind> {
  try {
    const stored = JSON.parse(localStorage.getItem(KINDS_KEY) ?? "null") as NoteKind[] | null;
    return new Set(stored ?? NOTE_KINDS.map(info => info.kind));
  } catch {
    return new Set(NOTE_KINDS.map(info => info.kind));
  }
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

type FormRequirementOf = Requirement & { items: FormItem[] };

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


function searchForm(requirement: FormRequirementOf, terms: string): Hit[] {
  return searchAnswers(requirement.name, requirement.items, terms, question => answerTo(requirement.items, question));
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
  const [ issuesOpen, setIssuesOpen ] = useState<boolean>();
  const [ railOpen, setRailOpen ] = useState(true);
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
  const data: ReviewData | undefined = useMemo(() => loaded && me ? parseReview(loaded.submission, schema, me)
    : undefined, [ loaded, schema, me ]);
  const groups = useMemo(() => loaded ? parseGroups(loaded.approvals, schema) : new Map<string, ReviewGroup[]>(),
    [ loaded, schema ]);
  const requirements = useMemo(() => (loaded?.form.requirements ?? []).filter(one =>
    isFormRequirement(one) || (isDocumentRequirement(one) && data?.documents.has(one.name))), [ loaded, data ]);
  const everyone = loaded?.everyone;
  const minePart = useCallback((part: string) => Boolean(everyone?.has(part.split("/")[0]))
    || isMine(part, groups, myGroups), [ everyone, groups, myGroups ]);
  const mine = useCallback((requirement: Requirement) => isFormRequirement(requirement)
    ? questions(requirement.items).some(question => minePart(question.path))
    : minePart(requirement.name), [ minePart ]);
  const listed = requirements;
  // The first requirement concerning the reviewer, until they choose another
  const current = requirements.find(one => one.name === selected) ?? requirements.find(mine) ?? requirements.at(0);

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


  const actions: ReviewActions = useMemo(() => ({
    write: async fields => {
      await writeComment(doFetch, path, fields);
      load();
    },
  }), [ doFetch, path, load ]);

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
    try {
      localStorage.setItem(KINDS_KEY, JSON.stringify([ ...next ]));
    } catch {
      // Remembered for this page only
    }
    return next;
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

  // What the search found, for the terms it was asked; shown only while those are still the terms
  const [ found, setFound ] = useState<{ terms: string; hits: Hit[] }>();
  const [ searching, setSearching ] = useState(false);
  const terms = query.trim();
  useEffect(() => {
    if (terms.length < 3 || !data) {
      return;
    }
    let current = true;
    const timer = setTimeout(() => {
      const answered = requirements.filter(isFormRequirement).flatMap(one => searchForm(one, terms));
      setFound({ terms, hits: answered });
      void Promise.all([ ...data.documents.entries() ].map(async ([ name, document ]) =>
        searchPages(name, document.title, await documentText(doFetch, document.url), terms)))
        .then(pages => current && setFound({ terms, hits: [ ...answered, ...pages.flat() ] }));
    }, 250);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [ terms, data, requirements, doFetch ]);
  const hits = searching && terms.length >= 3 && found?.terms === terms ? found.hits : undefined;

  const search = (inBar: boolean) => (
    <SubmissionSearch
      query={query} hits={hits} inBar={inBar} onQuery={setQuery}
      onOpen={() => setSearching(true)} onClose={() => setSearching(false)}
      onPick={hit => {
        setSearching(false);
        go(hit.requirement, { question: hit.question, page: hit.page });
      }}
    />
  );

  // The submission's title and where it stands, in the app bar, in view however far the reviewer scrolls, with the
  // search beside them on wide screens
  const header = loaded?.submission;
  usePublishedPageContext(header && {
    title: text(header.title),
    short: text(header.identifier),
    details: `${text(header.identifier)} · ${text(header.pi)}`,
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

  // The REB's review task, which the letter completes
  const rebTask = (): string | undefined => {
    const instances = loaded?.submission["wf:instances"];
    for (const instance of Object.values(typeof instances === "object" && instances ? instances : {})) {
      const task = typeof instance === "object" && instance ? (instance as Node).rebTask : undefined;
      if (typeof task === "object" && task && [ "created", "active" ].includes(text((task as Node).status))) {
        return text((task as Node)["@path"]);
      }
    }
    return undefined;
  };
  const sendLetter = async (letter: string) => {
    const task = rebTask();
    if (!task) {
      throw new Error("The REB's review is not waiting on a decision now.");
    }
    const body = new FormData();
    body.append("outcome", "changes-requested");
    body.append("outcomeNote", letter);
    const response = await doFetch(`${task}.json`, { method: "POST", body });
    if (!response.ok) {
      throw new Error(`The letter could not be sent (${response.status})`);
    }
    load();
  };

  if (error) {
    return <LoadError title="This submission could not be loaded" message={error} onRetry={retry} />;
  }
  if (!loaded || !data || !me) {
    return <LoadingOverlay open message="Loading the submission" />;
  }
  const submission = loaded.submission;
  // The reviewer's own issues: what their panel lists, and what their letter and escalation are made of
  const myIssues = data.notes.filter(note => note.kind === "issue" && note.mine);
  const shownDocument = current ? data.documents.get(current.name) : undefined;

  const rail = narrow ? (
    <TextField
      select fullWidth size="small" label="Requirement" value={current?.name ?? ""}
      onChange={event => go(event.target.value)}
    >
      {listed.map(requirement => (
        <MenuItem key={requirement.name} value={requirement.name}>
          {requirement.label} ({notesOf(requirement).filter(note => shown.has(note.kind)).length})
        </MenuItem>
      ))}
    </TextField>
  ) : (
    <List dense disablePadding aria-label="Requirements">
      {listed.map(requirement => {
        const count = notesOf(requirement).filter(note => shown.has(note.kind)).length;
        return (
          // The one being read says what it asks for under its name; the others on hover
          <Tooltip
            key={requirement.name} placement="right" describeChild
            title={requirement.name === current?.name ? "" : requirement.description ?? ""}
          >
            <ListItemButton
              selected={requirement.name === current?.name} onClick={() => go(requirement.name)}
              sx={{ borderRadius: 1, py: 0.25, alignItems: "flex-start" }}
            >
              <ListItemText
                primary={requirement.label}
                secondary={<>
                  {requirement.name === current?.name && requirement.description}
                  <Box component="span" sx={{ display: "block", mt: 0.25 }}>
                    <GroupChips groups={groupsOf(requirement)} mine={myGroups} />
                  </Box>
                </>}
                slotProps={{ primary: { variant: "body2" }, secondary: { variant: "caption", component: "div" } }}
                sx={{ my: 0.25 }}
              />
              {count > 0 && <Chip size="small" label={count} sx={{ ml: 1, height: 20 }} />}
            </ListItemButton>
          </Tooltip>
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
        {myGroups.includes("reb-reviewers") && (
          <Stack direction="row" spacing={1} sx={{ ml: { sm: "auto" } }}>
            <Button size="small" variant="outlined" onClick={() => setDialog("board")}>Escalate to the board</Button>
            <Button size="small" variant="contained" onClick={() => setDialog("letter")}>Letter of review</Button>
          </Stack>
        )}
      </Stack>
      <Stack direction={{ xs: "column", md: "row" }} sx={{ gap: 1.5, alignItems: "flex-start" }}>
        {railOpen || narrow ? (
          <Paper sx={{ p: 0.5, width: { xs: "100%", md: 240 }, flexShrink: 0, position: { md: "sticky" }, top: { md: 16 } }}>
            <Stack direction="row" sx={{ alignItems: "center", pl: 1.5, pr: 0.5, py: 0.5 }}>
              <Typography variant="subtitle2" component="h2" sx={{ flex: 1 }}>Submission contents</Typography>
              {!narrow && (
                <Tooltip title="Fold away">
                  <IconButton size="small" aria-label="Fold the contents away" onClick={() => setRailOpen(false)}>
                    <ChevronLeftOutlined />
                  </IconButton>
                </Tooltip>
              )}
            </Stack>
            {rail}
          </Paper>
        ) : (
          // Folded, it still says what it is
          <Paper sx={{ width: 48, py: 1, flexShrink: 0, position: "sticky", top: 16, display: "flex",
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
        <Paper sx={{ px: 2, py: 1.5, flex: 1, minWidth: 0, width: "100%" }}>
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
                      variant={shown.has(info.kind) ? "filled" : "outlined"}
                      onClick={() => toggleKind(info.kind)}
                      aria-pressed={shown.has(info.kind)}
                      sx={theme => ({
                        borderColor: theme.palette[info.mark].main,
                        ...shown.has(info.kind) ? { bgcolor: theme.alpha(theme.palette[info.mark].light, 0.45) } : {},
                      })}
                    />
                  ))}
                </Stack>
              </Stack>
              {isFormRequirement(current) && (
                <FormReview
                  items={current.items} data={data} groups={groups} myGroups={myGroups} shown={shown}
                  onEvidence={openEvidence} actions={actions}
                />
              )}
              {isDocumentRequirement(current) && shownDocument && (
                <Box sx={{ mt: 1 }}>
                  <DocumentReview
                    key={current.name}
                    document={shownDocument} data={data} shown={shown} selected={mark.id}
                    onSelect={id => setMark(currentMark => ({ ...currentMark, id }))} page={mark.page}
                    actions={actions} questionLabel={questionLabel}
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
                reviewer: myName })}
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
        <Box sx={{ display: { xs: "none", md: "block" }, position: "sticky", top: 16, flexShrink: 0 }}>
          <IssuesPanel
            issues={myIssues} about={about}
            open={issuesOpen ?? !(current && isDocumentRequirement(current))}
            onToggle={() => setIssuesOpen(!(issuesOpen ?? !(current && isDocumentRequirement(current))))}
            onOpenIssue={openIssue}
            actions={myGroups.includes("reb-reviewers")
              ? { letter: () => setDialog("letter"), board: () => setDialog("board") } : undefined}
          />
        </Box>
      </Stack>
    </Stack>
  );
}

