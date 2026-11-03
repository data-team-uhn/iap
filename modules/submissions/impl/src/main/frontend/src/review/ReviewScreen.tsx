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

import SearchOutlined from "@mui/icons-material/SearchOutlined";
import {
  Box,
  Chip,
  ClickAwayListener,
  FormControlLabel,
  InputAdornment,
  Link,
  List,
  ListItemButton,
  ListItemText,
  MenuItem,
  Paper,
  Stack,
  Switch,
  TextField,
  Typography,
  useMediaQuery,
  useTheme,
} from "@mui/material";
import { Link as RouterLink } from "react-router";

import LoadError from "@iap/frontend-commons/components/LoadError";
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
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
import FormReview, { answerText, type ReviewActions } from "./FormReview";
import { writeComment } from "./reviewApi";
import { isMine, NOTE_KINDS, notesAbout, parseGroups, parseReview, type NoteKind, type ReviewData,
  type ReviewGroup, type ReviewNote } from "./reviewData";
import { documentText, searchAnswers, searchPages, type Hit } from "./searchSubmission";
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

// PRETOTYPE. A submission as its reviewer works through it: their requirements down the side, in the schema's
// order, the one they are on in the middle, compact for a form and whole for a document, with everything
// written about it, of the kinds they choose to see. Everything else is one switch away.
export default function ReviewScreen({ path }: { path: string }) {
  const doFetch = useAuthenticatedFetch();
  const me = useCurrentUser();
  const myGroups = useMyGroups(me);
  const theme = useTheme();
  const narrow = useMediaQuery(theme.breakpoints.down("md"));
  const [ loaded, setLoaded ] = useState<Loaded>();
  const [ error, setError ] = useState<string>();
  const [ selected, setSelected ] = useState<string>();
  const [ whole, setWhole ] = useState(false);
  const [ shown, setShown ] = useState(storedKinds);
  const [ mark, setMark ] = useState<{ id?: string; page?: number }>({});
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
  const listed = requirements.filter(requirement => whole || mine(requirement));
  const current = requirements.find(one => one.name === selected) ?? listed.at(0);

  const notesOf = useCallback((requirement: Requirement): ReviewNote[] => {
    if (!data) {
      return [];
    }
    if (isFormRequirement(requirement)) {
      const uuids = questions(requirement.items)
        .filter(question => whole || minePart(question.path))
        .map(question => data.answers.get(question.path)?.uuid).filter((one): one is string => Boolean(one));
      return notesAbout(data.notes, uuids);
    }
    const document = data.documents.get(requirement.name);
    return document ? notesAbout(data.notes, [ document.uuid, document.version ]).filter(note => note.passage) : [];
  }, [ data, whole, minePart ]);

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

  if (error) {
    return <LoadError title="This submission could not be loaded" message={error} onRetry={retry} />;
  }
  if (!loaded || !data || !me) {
    return <LoadingOverlay open message="Loading the submission" />;
  }
  const submission = loaded.submission;
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
          <ListItemButton
            key={requirement.name} selected={requirement.name === current?.name} onClick={() => go(requirement.name)}
            sx={{ borderRadius: 1, opacity: mine(requirement) ? 1 : 0.55 }}
          >
            <ListItemText primary={requirement.label} />
            {count > 0 && <Chip size="small" label={count} sx={{ ml: 1 }} />}
          </ListItemButton>
        );
      })}
    </List>
  );

  return (
    <Stack spacing={2}>
      <Link component={RouterLink} to="/">← Back to your queue</Link>
      <Paper sx={{ p: 2 }}>
        <Stack direction={{ xs: "column", md: "row" }} sx={{ gap: 2, alignItems: { md: "flex-start" } }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Stack direction="row" sx={{ alignItems: "center", gap: 1, flexWrap: "wrap" }}>
              <Typography variant="pageTitle" component="h1">{text(submission.title)}</Typography>
              <TagChip tags={submission.tags} category="lifecycle" />
              <TagChip tags={submission.tags} category="priority" />
            </Stack>
            <Typography variant="body2" sx={{ color: "text.secondary" }}>
              {text(submission.identifier)} · {text(submission.pi)}
            </Typography>
          </Box>
          <Countdown due={submission.reviewDue} pausedAt={submission.reviewPausedAt} variant="subtitle1" />
        </Stack>
        <Stack direction={{ xs: "column", sm: "row" }} sx={{ gap: 2, mt: 2, alignItems: { sm: "center" } }}>
          <ClickAwayListener onClickAway={() => setSearching(false)}>
            <Box sx={{ position: "relative", flex: 1, maxWidth: { sm: 480 } }}>
              <TextField
                fullWidth size="small" value={query} placeholder="Search the answers and documents"
                onChange={event => {
                  setQuery(event.target.value);
                  setSearching(true);
                }}
                onFocus={() => setSearching(true)}
                slotProps={{ input: { startAdornment: <InputAdornment position="start"><SearchOutlined /></InputAdornment> },
                  htmlInput: { "aria-label": "Search the submission" } }}
              />
              {hits && (
                <Paper elevation={8} sx={{ position: "absolute", zIndex: "modal", left: 0, right: 0, mt: 0.5, maxHeight: 420,
                  overflowY: "auto" }}>
                  {hits.length === 0 && <Typography sx={{ p: 2 }} variant="body2">Nothing found yet</Typography>}
                  <List dense>
                    {hits.map(hit => (
                      <ListItemButton
                        key={`${hit.requirement}-${hit.question ?? hit.page ?? ""}`}
                        onClick={() => {
                          setSearching(false);
                          go(hit.requirement, { question: hit.question, page: hit.page });
                          if (hit.question && !minePart(hit.question)) {
                            setWhole(true);
                          }
                        }}
                      >
                        <ListItemText primary={hit.where} secondary={hit.snippet} />
                      </ListItemButton>
                    ))}
                  </List>
                </Paper>
              )}
            </Box>
          </ClickAwayListener>
          <FormControlLabel
            control={<Switch checked={whole} onChange={event => setWhole(event.target.checked)} />}
            label="Whole submission"
          />
        </Stack>
        <Stack direction="row" sx={{ gap: 1, flexWrap: "wrap", mt: 2 }} role="group" aria-label="Which notes to show">
          {NOTE_KINDS.map(info => (
            <Chip
              key={info.kind}
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
      </Paper>
      <Stack direction={{ xs: "column", md: "row" }} sx={{ gap: 2, alignItems: "flex-start" }}>
        <Paper sx={{ p: 1, width: { xs: "100%", md: 260 }, flexShrink: 0, position: { md: "sticky" }, top: { md: 16 } }}>
          {rail}
        </Paper>
        <Paper sx={{ p: 2, flex: 1, minWidth: 0, width: "100%" }}>
          {current && (
            <>
              <Typography variant="h6" component="h2">{current.label}</Typography>
              {current.description && <Typography variant="description">{current.description}</Typography>}
              {isFormRequirement(current) && (
                <FormReview
                  items={current.items} data={data} groups={groups} myGroups={myGroups} isMine={minePart}
                  whole={whole} shown={shown} onEvidence={openEvidence} actions={actions}
                />
              )}
              {isDocumentRequirement(current) && shownDocument && (
                <Box sx={{ mt: 2 }}>
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
          {!current && (
            <Typography>Nothing here is for your group. Switch to the whole submission to see it all.</Typography>
          )}
        </Paper>
      </Stack>
    </Stack>
  );
}

