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

import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from "react";


import AddCommentOutlined from "@mui/icons-material/AddCommentOutlined";
import ArticleOutlined from "@mui/icons-material/ArticleOutlined";
import ErrorOutlineOutlined from "@mui/icons-material/ErrorOutlineOutlined";
import { Box, ButtonBase, IconButton, Link, Stack, Tooltip, Typography } from "@mui/material";
import { createPortal } from "react-dom";

import { keepInView } from "@iap/documents-pdf-viewer/markLayer";
import {
  cutShort, getSelectedOutline, passageCard, passageDetails, passageHover, passageLayer, passageTint,
} from "@iap/documents-pdf-viewer/passageStyles";
import StepControls from "@iap/documents-pdf-viewer/StepControls";
import SplitHandle from "@iap/frontend-commons/components/SplitHandle";
import { useElementWidth } from "@iap/frontend-commons/useElementWidth";
import { fadeEdges, useScrollEdges } from "@iap/frontend-commons/useScrollEdges";

import { type FormItem, type FormQuestion, isQuestion } from "../submissionForm";
import Composer, { WRITTEN } from "./Composer";
import NoteCard, { DraftHeading, NoteActions, NoteHeading, NoteSummary } from "./NoteCard";
import { GroupChip } from "./people";
import { groupShortName, kindInfo, raiseAsIssue, notesAbout, type NoteKind, type ReviewData, type ReviewGroup,
  type ReviewNote } from "./reviewData";
import { Highlighted } from "./SearchHighlight";

// How wide the form must be for a question's notes to go beside it, as a document's are beside its pages, rather
// than under it; their width is the reader's, set on the whole form; and the gap between, in spacing units
const WIDE = "@container (min-width: 640px)";
const NOTES_WIDTH = "--notes-width";
const GAP = 3;
// How narrow the notes may be dragged, and how much the questions keep, the gap included
const NOTES_MIN_WIDTH = 240;
const QUESTIONS_MIN_WIDTH = 344;
// How wide the form must be to list its notes beside it, as a document lists its notes, rather than under each
// question: enough for both at their narrowest
const LISTED_WIDTH = QUESTIONS_MIN_WIDTH + NOTES_MIN_WIDTH;
// The note being written, which the list shows where its question's notes go
const DRAFT = "draft";

// PRETOTYPE. A wide form lists its notes beside it as a document does: the note selected, and the note being
// written, are shared by the questions, which tint their answers, and the list
interface Listing {
  selected?: string;
  select: (id: string | undefined) => void;
  draftFor?: string;
  startDraft: (question: string) => void;
  // The notes listed for each question, by its path, in the order listed
  notesOf: (question: string) => ReviewNote[];
}
const FormNotes = createContext<Listing | undefined>(undefined);

// The notes shown for a question: a contradiction first, then whatever else is about it, where it is the first of the
// questions a note is about. Its source is not a note here: the answer itself leads to it
function notesBeside(question: FormQuestion, data: ReviewData, shown: Set<NoteKind>,
  homeOf: Map<string, string>): ReviewNote[] {
  const answer = data.answers.get(question.path);
  const notes = (answer ? notesAbout(data.notes, [ answer.uuid ]) : []).filter(note => note.kind !== "evidence"
    && shown.has(note.kind) && (homeOf.get(note.id) ?? question.path) === question.path);
  return [ ...notes.filter(note => note.kind === "contradiction"), ...notes.filter(note => note.kind !== "contradiction") ];
}

// The questions of a form, every one, section by section
function questionsOf(items: FormItem[]): FormQuestion[] {
  return items.flatMap(item => isQuestion(item) ? [ item ] : questionsOf(item.items));
}

// What the AI read for a question, as its options call it
function extractedText(question: FormQuestion, note: ReviewNote | undefined): string | undefined {
  return note?.kind === "evidence" || note?.kind === "contradiction"
    ? answerText(question, note.extracted?.split(";").map(one => one.trim()) ?? []) : undefined;
}

export interface ReviewActions {
  write: (fields: Record<string, string | string[]>) => Promise<void>;
}

// An answer as the submitter sees its options: the labels, not the stored values
export function answerText(question: FormQuestion, values: string[]): string {
  if (values.length === 0) {
    return "Not answered";
  }
  if (question.dataType === "boolean") {
    return values[0] === "true" ? "Yes" : "No";
  }
  const labels = values.map(value => question.options.find(option => option.value === value)?.label ?? value);
  return labels.join("; ");
}

// Whether a question's answers can be compared as values, a choice, a date, a number, rather than in words
export function comparable(question: FormQuestion): boolean {
  return question.options.length > 0 || [ "date", "boolean", "long", "double" ].includes(question.dataType);
}

// Whether what the AI read out of the protocol disagrees with the answer. Only for answers that can be compared
// as values: free text is paraphrased either way.
export function disagrees(question: FormQuestion, extracted: string | undefined): boolean {
  if (!extracted || !comparable(question)) {
    return false;
  }
  const normal = (values: string[]) => values.map(value => value.trim().toLowerCase()).sort().join(";");
  return normal(extracted.split(";")) !== normal(question.value);
}

// The reviewer groups something matters to: the reviewer's own first and filled, the others on a quiet tint
export function GroupChips({ groups, mine }: { groups: ReviewGroup[] | undefined; mine: string[] }) {
  if (!groups?.length) {
    return null;
  }
  return (
    <Stack direction="row" sx={{ gap: 0.5, flexWrap: "wrap" }}>
      {[ ...groups ].sort((a, b) => Number(mine.includes(b.group)) - Number(mine.includes(a.group))).map(group => (
        <GroupChip key={group.name} label={groupShortName(group.label)} mine={mine.includes(group.group)} />
      ))}
    </Stack>
  );
}

// PRETOTYPE. One question, compact: what was asked, what was answered, which groups asked it, where the answer
// was found in the protocol, and everything written about it.
function QuestionReview({ question, data, groups, myGroups, shown, onEvidence, actions, homeOf, formLabel }: {
  question: FormQuestion;
  formLabel?: string;
  homeOf: Map<string, string>;
  data: ReviewData;
  groups: Map<string, ReviewGroup[]>;
  myGroups: string[];
  shown: Set<NoteKind>;
  onEvidence: (note: ReviewNote) => void;
  actions: ReviewActions;
}) {
  const listing = useContext(FormNotes);
  const [ ownWriting, setOwnWriting ] = useState(false);
  const writing = listing ? listing.draftFor === question.path : ownWriting;
  const setWriting = (on: boolean) => {
    if (listing) {
      listing.startDraft(question.path);
    } else {
      setOwnWriting(on);
    }
  };
  // The AI pre-review note being made into an issue, coloured as one meanwhile
  const [ adopting, setAdopting ] = useState<string>();
  // The reviewer's own note being changed, whose card then shows its editor
  const [ editing, setEditing ] = useState<string>();
  const answer = data.answers.get(question.path);
  const about = answer ? notesAbout(data.notes, [ answer.uuid ]) : [];
  const source = about.find(note => note.kind === "evidence");
  const contradiction = about.find(note => note.kind === "contradiction");
  const extracted = extractedText(question, contradiction);
  const side = notesBeside(question, data, shown, homeOf);
  // Listed beside the form, its notes tint the answer, in the colour of the first, and the selected one outlines it;
  // a click on the answer selects its first note, then the next
  const listed = listing ? listing.notesOf(question.path) : [];
  const selectedHere = writing && listing?.selected === DRAFT ? undefined
    : listed.find(note => note.id === listing?.selected);
  const tint = writing ? "draft" : listed.length > 0 ? kindInfo(listed[0].kind).mark : undefined;
  const outline = writing && listing?.selected === DRAFT ? "draft"
    : selectedHere ? kindInfo(selectedHere.kind).mark : undefined;
  const cycle = () => {
    if (listing && listed.length > 0) {
      const at = listed.findIndex(note => note.id === listing.selected);
      listing.select(listed[(at + 1) % listed.length].id);
    }
  };
  return (
    // Beside the question, its notes, as a document has them beside its pages; under it when the page is narrow, or
    // in the list beside the whole form
    <Box
      id={`question-${question.path}`}
      sx={listing ? { py: 1.5, scrollMarginTop: 96 } : { py: 1.5, scrollMarginTop: 96, display: "grid",
        gridTemplateColumns: "minmax(0, 1fr)", rowGap: 1.5,
        [WIDE]: { gridTemplateColumns: `minmax(0, 1fr) var(${NOTES_WIDTH})`, columnGap: GAP } }}
    >
      {/* Shaded under the pointer, and while a note is being written about it, so its comment button and the note
          being written are seen to belong to it; the shade reaches a little past the words */}
      <Stack sx={theme => ({ gap: 0.5, minWidth: 0, mx: -1, px: 1, my: -0.75, py: 0.75, borderRadius: 1,
        transition: theme.transitions.create("background-color", { duration: theme.transitions.duration.shortest }),
        ...writing ? { bgcolor: "action.hover" } : {}, "&:hover": { bgcolor: "action.hover" } })}>
        <Stack direction="row" sx={{ minWidth: 0, gap: 0.75, flexWrap: "wrap", alignItems: "baseline" }}>
          <Typography variant="body2" sx={{ fontWeight: "fontWeightBold" }}>
            <Highlighted text={question.text} question={question.path} field="question" />
          </Typography>
          <GroupChips groups={groups.get(question.path)} mine={myGroups} />
        </Stack>
        <Stack direction="row" sx={{ minWidth: 0, gap: 1, alignItems: "flex-start" }}>
          <Box
            role={tint ? "presentation" : undefined} onClick={tint ? cycle : undefined}
            sx={theme => ({ flex: 1, minWidth: 0, ...tint ? {
              mx: -0.5, px: 0.5, borderRadius: 0.5, cursor: "pointer", bgcolor: passageTint(theme, tint, 0.2),
              outline: outline ? "2px solid" : "none", outlineColor: outline ? getSelectedOutline(theme, outline) : undefined,
            } : {} })}
          >
            <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>
              <Highlighted text={answerText(question, question.value)} question={question.path} field="answer" />
            </Typography>
            {/* An answer the protocol contradicts says so, always; one linked to a passage leads there, when linked answers
                are shown */}
            {contradiction ? (
              <Link
                component="button" variant="caption" underline="hover" onClick={() => onEvidence(contradiction)}
                sx={{ display: "flex", alignItems: "center", gap: 0.5, color: "warning.main", mt: 0.25,
                  textAlign: "start" }}
              >
                <ErrorOutlineOutlined sx={{ fontSize: "1rem" }} />
                The protocol says {extracted}{contradiction.passage?.page ? `, page ${contradiction.passage.page}` : ""}
              </Link>
            ) : source && shown.has("evidence") && (
              <Link
                component="button" variant="caption" underline="hover" onClick={() => onEvidence(source)}
                sx={{ display: "flex", alignItems: "center", gap: 0.5, color: "evidence.main", mt: 0.25,
                  textAlign: "start" }}
              >
                <ArticleOutlined sx={{ fontSize: "1rem" }} />
                In the protocol{source.passage?.page ? `, page ${source.passage.page}` : ""}
              </Link>
            )}
          </Box>
          {!writing && (
            <Tooltip title="Write about this">
              <IconButton
                size="small" onClick={() => setWriting(true)} sx={{ mt: -0.5 }}
                aria-label={`Write about: ${question.text}`}
              >
                <AddCommentOutlined fontSize="small" />
              </IconButton>
            </Tooltip>
          )}
        </Stack>
      </Stack>
      {!listing && (side.length > 0 || writing) && (
        <Stack spacing={1} sx={{ minWidth: 0 }}>
          {side.map(note => (
            <SideCard key={note.id} note={note} kind={adopting === note.id ? "issue" : note.kind}
              onOpen={note.passage ? () => onEvidence(note) : undefined}
              actions={editing !== note.id && (
                <NoteActions
                  note={note} onEdit={() => setEditing(note.id)} onRemove={() => actions.write({ remove: note.id })}
                />
              )}>
              <NoteCard
                note={note} answerLabel={extracted} formAnswer={answerText(question, question.value)} headed
                askedAs={{ question: question.text, form: formLabel }}
                onReply={text => actions.write({ replyTo: note.id, text })}
                onEditReply={(reply, text) => actions.write({ edit: reply, text })}
                onRemoveReply={reply => actions.write({ remove: reply })}
                onEdit={(text, severity, topic) => actions.write({ edit: note.id, text, ...severity ? { severity } : {},
                  ...topic !== undefined ? { topic } : {} })}
                onRemove={() => actions.write({ remove: note.id })}
                onMakeIssue={(text, severity, topic) => raiseAsIssue(actions.write, note, text, severity, topic)}
                onDismiss={() => actions.write({ dismiss: note.id })}
                write={actions.write}
                adopting={adopting === note.id}
                onAdopting={on => setAdopting(on ? note.id : undefined)}
                editing={editing === note.id}
                onEditing={on => setEditing(on ? note.id : undefined)}
              />
            </SideCard>
          ))}
          {writing && answer && (
            // On a draft's card, as a document shows a note being started
            <Box sx={theme => ({ ...passageCard(theme, "draft"), px: 1.5, py: 1.25, outline: "2px solid",
              outlineColor: getSelectedOutline(theme, "draft"), outlineOffset: "-2px" })}>
              <Composer
                kinds={WRITTEN} initialKind="issue" about={[ question.path ]}
                onCancel={() => setWriting(false)}
                onSave={async (kind, text, severity, topic) => {
                  await actions.write({ kind, text, subjects: [ answer.uuid ], ...severity ? { severity } : {},
                    ...topic ? { topic } : {} });
                  setWriting(false);
                }}
              />
            </Box>
          )}
        </Stack>
      )}
    </Box>
  );
}

// A note beside a question, on a card like a document's notes: when it points at a passage of a document, the
// passage heads it and leads there
function SideCard({ note, kind, onOpen, actions, children }: {
  note: ReviewNote;
  kind: NoteKind;
  onOpen?: () => void;
  // Buttons at the end of the heading, kept out of the button the heading is when it leads to a passage
  actions?: ReactNode;
  children: ReactNode;
}) {
  const mark = kindInfo(kind).mark;
  const hasActions = actions !== undefined && actions !== null && actions !== false;
  const strip = { display: "block", width: "100%", px: 1.5, pr: hasActions ? 10 : 1.5, py: 1, textAlign: "start" };
  return (
    <Box data-note={note.id} sx={theme => ({ ...passageCard(theme, mark), overflow: "hidden", position: "relative" })}>
      {hasActions && <Box sx={{ position: "absolute", top: 4, right: 12, zIndex: 1 }}>{actions}</Box>}
      {/* Headed by what it is, when and by whom, as beside a document; on a passage, the passage follows, leading
          to it, since the form does not show it */}
      {note.passage && onOpen ? (
        <ButtonBase
          onClick={onOpen}
          aria-label={`Show it in the document${note.passage.page ? `, page ${note.passage.page}` : ""}`}
          sx={theme => ({ ...strip, ...passageLayer(theme, mark),
            "@media (hover: hover)": { "&:hover": { bgcolor: passageHover(theme, mark) } },
            "&.Mui-focusVisible": { bgcolor: passageHover(theme, mark) } })}
        >
          <Box sx={{ mb: 0.5 }}><NoteHeading note={note} /></Box>
          <Typography variant="body2" component="span" sx={cutShort(3)}>“{note.passage.quote}”</Typography>
          <Typography variant="caption" component="span" sx={{ display: "flex", alignItems: "center", gap: 0.5,
            color: "text.secondary" }}>
            <ArticleOutlined sx={{ fontSize: "1rem" }} />
            In the protocol{note.passage.page ? `, page ${note.passage.page}` : ""}
          </Typography>
        </ButtonBase>
      ) : (
        <Box sx={theme => ({ ...strip, ...passageLayer(theme, mark) })}><NoteHeading note={note} /></Box>
      )}
      <Box sx={{ px: 1.5, py: 1 }}>{children}</Box>
    </Box>
  );
}

// The question each note shows beside: the first it is about, as its author listed them, among those this form asks
function homes(items: FormItem[], data: ReviewData): Map<string, string> {
  const asked = new Map<string, string>();
  const visit = (list: FormItem[]) => list.forEach(item => {
    if (!isQuestion(item)) {
      visit(item.items);
      return;
    }
    const answer = data.answers.get(item.path);
    if (answer) {
      asked.set(answer.uuid, item.path);
    }
  });
  visit(items);
  const home = new Map<string, string>();
  for (const note of data.notes) {
    const first = [ ...note.subjects, ...note.passage?.source ? [ note.passage.source ] : [] ]
      .map(subject => asked.get(subject)).find(Boolean);
    if (first) {
      home.set(note.id, first);
    }
  }
  return home;
}

// The questions of a form requirement, section by section, every one of them: the group chips say which concern
// the reviewer.
export default function FormReview({ items, data, groups, myGroups, shown, onEvidence, actions, depth = 0, homeOf,
  notesWidth = 320, onNotesWidth, selected, onSelect, heading, notesSlot, formLabel }: {
  items: FormItem[];
  data: ReviewData;
  groups: Map<string, ReviewGroup[]>;
  myGroups: string[];
  shown: Set<NoteKind>;
  onEvidence: (note: ReviewNote) => void;
  actions: ReviewActions;
  depth?: number;
  // Which question each note shows under, worked out once for the whole form
  homeOf?: Map<string, string>;
  // How wide the notes beside the questions are, which the reader sizes by dragging the edge between them
  notesWidth?: number;
  onNotesWidth?: (width: number) => void;
  // The note selected in the list beside a wide form, shared with whoever sends the reader to one
  selected?: string;
  onSelect?: (id: string | undefined) => void;
  // What heads the questions' column, the title of the page they are on, beside their notes
  heading?: ReactNode;
  // PRETOTYPE: a column of the screen's own to list the notes in, beside however many pages it shows; null while
  // another page's notes are listed there, when this one lists none
  notesSlot?: HTMLElement | null;
  // The form's title, as an issue made of a contradiction names it
  formLabel?: string;
}) {
  const home = homeOf ?? homes(items, data);
  const form = useRef<HTMLElement>(null);
  const [ draftFor, setDraftFor ] = useState<string>();
  // The width the reader chose, as far as this form has room for it
  const formWidth = useElementWidth(form);
  const width = formWidth === undefined ? notesWidth
    : Math.max(NOTES_MIN_WIDTH, Math.min(notesWidth, formWidth - QUESTIONS_MIN_WIDTH));
  const body = (
    <>
      {items.map(item => {
        if (isQuestion(item)) {
          return (
            <QuestionReview
              key={item.path} question={item} data={data} groups={groups} myGroups={myGroups} shown={shown}
              onEvidence={onEvidence} actions={actions} homeOf={home} formLabel={formLabel}
            />
          );
        }
        return (
          <Box key={item.name} sx={{ mt: 3 }}>
            <Typography variant="subheading" component={depth === 0 ? "h3" : "h4"}>{item.label}</Typography>
            <FormReview
              items={item.items} data={data} groups={groups} myGroups={myGroups} shown={shown}
              onEvidence={onEvidence} actions={actions} depth={depth + 1} homeOf={home} formLabel={formLabel}
            />
          </Box>
        );
      })}
    </>
  );
  // The questions go side by side with their notes by the width the form has, whatever folds around it
  if (depth > 0) {
    return body;
  }
  // Wide, as a form is until measured on a wide screen, it lists its notes beside it
  const asked = questionsOf(items);
  const beside = new Map(asked.map(question => [ question.path, notesBeside(question, data, shown, home) ]));
  if (onSelect && (notesSlot !== undefined || (formWidth ?? LISTED_WIDTH) >= LISTED_WIDTH)) {
    const listing: Listing = {
      selected, select: onSelect, draftFor,
      startDraft: question => {
        setDraftFor(question);
        onSelect(DRAFT);
      },
      notesOf: question => beside.get(question) ?? [],
    };
    const entries: ListEntry[] = asked.flatMap(question => [
      ...(beside.get(question.path) ?? []).map(note => ({ question, note })),
      ...draftFor === question.path ? [ { question } ] : [],
    ]);
    const list = (
      <FormNoteList
        entries={entries} selected={selected} onSelect={onSelect} width={notesSlot ? "100%" : width} data={data}
        formLabel={formLabel}
        actions={actions} onEvidence={onEvidence} onDraftDone={() => {
          setDraftFor(undefined);
          onSelect(undefined);
        }}
      />
    );
    if (notesSlot !== undefined) {
      return (
        <FormNotes.Provider value={listing}>
          <Box ref={form} sx={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0 }}>
            {heading ? <Box sx={{ pl: 4, pr: 2, pt: 3 }}>{heading}</Box> : <Box sx={{ pt: 1.5 }} />}
            <Questions>{body}</Questions>
          </Box>
          {notesSlot && createPortal(list, notesSlot)}
        </FormNotes.Provider>
      );
    }
    return (
      <FormNotes.Provider value={listing}>
        {/* The questions scroll on their own; the notes go down the page's right edge, as tall as it */}
        <Box ref={form} sx={{ display: "flex", height: "100%", minHeight: 0 }}>
          <Box sx={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column" }}>
            {heading && <Box sx={{ pl: 4, pr: 1, pt: 3 }}>{heading}</Box>}
            <Questions>{body}</Questions>
          </Box>
          {onNotesWidth ? (
            <SplitHandle
              width={width} onWidth={onNotesWidth} container={() => form.current} min={NOTES_MIN_WIDTH}
              reserve={QUESTIONS_MIN_WIDTH} label="Resize the notes" sx={() => ({ alignSelf: "stretch" })}
            />
          ) : <Box sx={{ width: 16 }} />}
          {list}
        </Box>
      </FormNotes.Provider>
    );
  }
  return (
    <Box ref={form} data-form-scroller sx={{ containerType: "inline-size", position: "relative",
      [NOTES_WIDTH]: `${width}px`, height: { md: "100%" }, overflowY: { md: "auto" }, px: { md: 4 }, py: { md: 3 } }}>
      {heading}
      {body}
      {/* Down the gap between the questions and their notes, where they sit side by side */}
      {onNotesWidth && (
        <SplitHandle
          width={width} onWidth={onNotesWidth} container={() => form.current} min={NOTES_MIN_WIDTH}
          reserve={QUESTIONS_MIN_WIDTH} label="Resize the notes"
          sx={theme => ({ display: "none", position: "absolute", top: 0, bottom: 0,
            right: `calc(${width}px + ${theme.spacing(GAP / 2)} - 8px)`, [WIDE]: { display: "block" } })}
        />
      )}
    </Box>
  );
}

// The questions of a wide form, under its title, scrolling on their own, faded where there are more
function Questions({ children }: { children: ReactNode }) {
  const box = useRef<HTMLDivElement>(null);
  const edges = useScrollEdges(box);
  return (
    <Box ref={box} data-form-scroller
      sx={{ flex: 1, minHeight: 0, overflowY: "auto", pl: 4, pr: 1, pb: 3, maskImage: fadeEdges(edges) }}>
      {children}
    </Box>
  );
}

// A note listed beside a form, with the question it is about, or, without a note, the one being written about it
interface ListEntry {
  question: FormQuestion;
  note?: ReviewNote;
}

// Whether an element is out of what scrolls it, the form's own scroller, or the window on a narrow screen
function outOfView(element: HTMLElement): boolean {
  const box = element.getBoundingClientRect();
  const scroller = element.closest<HTMLElement>("[data-form-scroller]");
  const { top, bottom } = scroller && scroller.scrollHeight > scroller.clientHeight
    ? scroller.getBoundingClientRect() : { top: 96, bottom: window.innerHeight };
  return box.bottom < top || box.top > bottom;
}

// A note not selected, cut short past some lines and faded where it is; a click anywhere on it selects it, as its
// heading does for the keyboard
function CutShort({ lines, onOpen, children }: { lines: number; onOpen: () => void; children: ReactNode }) {
  const box = useRef<HTMLDivElement>(null);
  const edges = useScrollEdges(box);
  return (
    <Box ref={box} role="presentation" onClick={onOpen} sx={{ ...passageDetails(edges, lines), cursor: "pointer" }}>
      {children}
    </Box>
  );
}

// PRETOTYPE. The notes of a wide form listed beside it, as a document's are: in the order of the questions they are
// about, one selected and open, the others cut short, with the note being written where its question's notes go.
// Selecting one brings its question into view, whose answer it outlines.
function FormNoteList({ entries, selected, onSelect, width, data, actions, onEvidence, onDraftDone, formLabel }: {
  entries: ListEntry[];
  formLabel?: string;
  selected?: string;
  onSelect: (id: string | undefined) => void;
  width: number | string;
  data: ReviewData;
  actions: ReviewActions;
  onEvidence: (note: ReviewNote) => void;
  onDraftDone: () => void;
}) {
  const list = useRef<HTMLUListElement>(null);
  const placed = useRef(new Map<string, HTMLElement>());
  const edges = useScrollEdges(list);
  const [ adopting, setAdopting ] = useState<string>();
  const [ editing, setEditing ] = useState<string>();
  const ids = entries.map(entry => entry.note?.id ?? DRAFT);
  const question = entries.find(entry => (entry.note?.id ?? DRAFT) === selected)?.question.path;
  useEffect(() => {
    const entry = selected === undefined ? undefined : placed.current.get(selected);
    if (list.current && entry) {
      keepInView(list.current, entry);
    }
    const asked = question ? document.getElementById(`question-${question}`) : null;
    if (asked && outOfView(asked)) {
      asked.scrollIntoView({ block: "center", behavior: "smooth" });
    }
  }, [ selected, question ]);
  const place = (id: string) => (element: HTMLElement | null) => {
    if (element) {
      placed.current.set(id, element);
    } else {
      placed.current.delete(id);
    }
  };

  const count = entries.filter(entry => entry.note).length;
  const position = ids.indexOf(selected ?? "") + 1;
  const total = count === 1 ? "1 note" : `${count} notes`;
  return (
    // On the contents' tint, as a panel of its own beside the page, as tall as the page
    <Box sx={{ width, flexShrink: 0, height: "100%", display: "flex", flexDirection: "column", minHeight: 0,
      bgcolor: "background.tinted", px: 0.5, pt: 0.5 }}>
      {entries.length > 0 ? (
        <StepControls
          position={position} count={entries.length} itemName="note"
          heading={position > 0 ? `Note ${position} of ${entries.length}` : total}
          widest={[ `Note ${entries.length} of ${entries.length}`, total ]}
          onStep={next => onSelect(ids[next - 1])}
        />
      ) : (
        <Typography variant="body2" sx={{ color: "text.secondary", textAlign: "center", py: 1.5 }}>
          No notes on this part yet
        </Typography>
      )}
      <Box
        ref={list} component="ul" aria-label="Notes"
        sx={{ flex: "1 1 auto", minHeight: 0, overflow: "auto", listStyle: "none", m: 0, p: 0.5, display: "flex",
          flexDirection: "column", gap: 1, maskImage: fadeEdges(edges) }}
      >
        {entries.map(({ question, note }) => {
          if (!note) {
            const answer = data.answers.get(question.path);
            return (
              <Box component="li" key={DRAFT} ref={place(DRAFT)}
                sx={theme => ({ ...passageCard(theme, "draft", 0.5), flexShrink: 0, px: 1.5, py: 1.25, outline: "2px solid",
                  outlineColor: getSelectedOutline(theme, "draft"), outlineOffset: "-2px" })}>
                <Box sx={{ mb: 1 }}><DraftHeading /></Box>
                <Composer
                  kinds={WRITTEN} initialKind="issue" about={[ question.path ]}
                  onCancel={onDraftDone}
                  onSave={async (kind, text, severity, topic) => {
                    await actions.write({ kind, text, subjects: answer ? [ answer.uuid ] : [],
                      ...severity ? { severity } : {}, ...topic ? { topic } : {} });
                    onDraftDone();
                  }}
                />
              </Box>
            );
          }
          const mark = kindInfo(adopting === note.id ? "issue" : note.kind).mark;
          const isSelected = selected === note.id;
          const extracted = extractedText(question, note);
          return (
            <Box
              component="li" key={note.id} ref={place(note.id)}
              sx={theme => ({ ...passageCard(theme, mark, isSelected ? 0.5 : 0.25), position: "relative", flexShrink: 0,
                overflow: "hidden", outline: isSelected ? "2px solid" : "none",
                outlineColor: getSelectedOutline(theme, mark), outlineOffset: "-2px" })}
            >
              {editing !== note.id && (
                <Box sx={{ position: "absolute", top: 4, right: 12, zIndex: 1 }}>
                  <NoteActions
                    note={note}
                    onEdit={() => {
                      setEditing(note.id);
                      onSelect(note.id);
                    }}
                    onRemove={() => actions.write({ remove: note.id })}
                  />
                </Box>
              )}
              {/* Headed by what it is, when and by whom; an extracted answer also by the words it was read from */}
              <ButtonBase
                onClick={() => onSelect(note.id)} aria-current={isSelected ? "true" : undefined}
                aria-label={`Select the note about: ${question.text}`}
                sx={theme => ({ display: "block", width: "100%", px: 1.5, pr: note.mine ? 10 : 1.5, py: 1,
                  textAlign: "start", ...passageLayer(theme, mark),
                  "@media (hover: hover)": { "&:hover": { bgcolor: passageHover(theme, mark) } },
                  "&.Mui-focusVisible": { bgcolor: passageHover(theme, mark) } })}
              >
                <NoteHeading note={note} />
                {(note.kind === "evidence" || note.kind === "contradiction") && note.passage && (
                  <Typography variant="body2" component="span" sx={{ mt: 0.5, ...isSelected ? { display: "block" }
                    : cutShort(2) }}>
                    “{note.passage.quote}”
                  </Typography>
                )}
              </ButtonBase>
              {isSelected ? (
                <Box data-note={note.id} sx={{ px: 1.5, pt: 1, pb: 1 }}>
                  <NoteCard
                    note={note} answerLabel={extracted} formAnswer={answerText(question, question.value)} headed
                    askedAs={{ question: question.text, form: formLabel }}
                    onReply={text => actions.write({ replyTo: note.id, text })}
                    onEditReply={(reply, text) => actions.write({ edit: reply, text })}
                    onRemoveReply={reply => actions.write({ remove: reply })}
                    onEdit={(text, severity, topic) => actions.write({ edit: note.id, text,
                      ...severity ? { severity } : {}, ...topic !== undefined ? { topic } : {} })}
                    onRemove={() => actions.write({ remove: note.id })}
                    onMakeIssue={(text, severity, topic) => raiseAsIssue(actions.write, note, text, severity, topic)}
                    onDismiss={() => actions.write({ dismiss: note.id })}
                    write={actions.write}
                    adopting={adopting === note.id}
                    onAdopting={on => setAdopting(on ? note.id : undefined)}
                    editing={editing === note.id}
                    onEditing={on => setEditing(on ? note.id : undefined)}
                  />
                  {/* On a passage of the protocol, which the form does not show, it leads there */}
                  {note.passage && (
                    <Link
                      component="button" variant="caption" underline="hover" onClick={() => onEvidence(note)}
                      sx={{ display: "flex", alignItems: "center", gap: 0.5, mt: 0.5 }}
                    >
                      <ArticleOutlined sx={{ fontSize: "1rem" }} />
                      In the protocol{note.passage.page ? `, page ${note.passage.page}` : ""}
                    </Link>
                  )}
                </Box>
              ) : (
                <Box data-note={note.id}>
                  <CutShort lines={note.kind === "contradiction" ? 3 : 4} onOpen={() => onSelect(note.id)}>
                    {note.kind === "contradiction"
                      ? (
                        <Box sx={{ px: 1.5, pb: 1 }}>
                          <NoteCard
                            note={note} answerLabel={extracted} formAnswer={answerText(question, question.value)} headed
                          />
                        </Box>
                      )
                      : <NoteSummary note={note} headed />}
                  </CutShort>
                </Box>
              )}
            </Box>
          );
        })}
      </Box>
    </Box>
  );
}
