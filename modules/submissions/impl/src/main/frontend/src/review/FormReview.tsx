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

import { useRef, useState, type ReactNode } from "react";

import AddCommentOutlined from "@mui/icons-material/AddCommentOutlined";
import ArticleOutlined from "@mui/icons-material/ArticleOutlined";
import ErrorOutlineOutlined from "@mui/icons-material/ErrorOutlineOutlined";
import { Box, ButtonBase, IconButton, Link, Stack, Tooltip, Typography } from "@mui/material";

import { cutShort, getSelectedOutline, passageCard, passageLayer, passageTint } from "@iap/documents-pdf-viewer/passageStyles";
import SplitHandle from "@iap/frontend-commons/components/SplitHandle";
import { useElementWidth } from "@iap/frontend-commons/useElementWidth";

import { type FormItem, type FormQuestion, isQuestion } from "../submissionForm";
import Composer, { WRITTEN } from "./Composer";
import NoteCard from "./NoteCard";
import { GroupChip } from "./people";
import { groupShortName, issueFrom, kindInfo, notesAbout, type NoteKind, type ReviewData, type ReviewGroup,
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

// Whether what the AI read out of the protocol disagrees with the answer. Only for answers that can be compared
// as values: free text is paraphrased either way.
export function disagrees(question: FormQuestion, extracted: string | undefined): boolean {
  if (!extracted || (question.options.length === 0 && ![ "date", "boolean", "long", "double" ].includes(question.dataType))) {
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
function QuestionReview({ question, data, groups, myGroups, shown, onEvidence, actions, homeOf }: {
  question: FormQuestion;
  homeOf: Map<string, string>;
  data: ReviewData;
  groups: Map<string, ReviewGroup[]>;
  myGroups: string[];
  shown: Set<NoteKind>;
  onEvidence: (note: ReviewNote) => void;
  actions: ReviewActions;
}) {
  const [ writing, setWriting ] = useState(false);
  // The AI pre-review note being made into an issue, coloured as one meanwhile
  const [ adopting, setAdopting ] = useState<string>();
  const answer = data.answers.get(question.path);
  const notes = answer ? notesAbout(data.notes, [ answer.uuid ]) : [];
  const evidence = notes.find(note => note.kind === "evidence");
  const differs = disagrees(question, evidence?.extracted);
  // A note about several answers shows under the first of them only
  const others = notes.filter(note => note.kind !== "evidence" && shown.has(note.kind)
    && (homeOf.get(note.id) ?? question.path) === question.path);
  const extracted = evidence ? answerText(question, evidence.extracted?.split(";").map(one => one.trim()) ?? [])
    : undefined;
  const side = [ ...evidence && shown.has("evidence") ? [ evidence ] : [], ...others ];
  return (
    // Beside the question, its notes, as a document has them beside its pages; under it when the page is narrow
    <Box
      id={`question-${question.path}`}
      sx={{ py: 1.5, scrollMarginTop: 96, display: "grid", gridTemplateColumns: "minmax(0, 1fr)", rowGap: 1.5,
        [WIDE]: { gridTemplateColumns: `minmax(0, 1fr) var(${NOTES_WIDTH})`, columnGap: GAP } }}
    >
      <Stack sx={{ gap: 0.5, minWidth: 0 }}>
        <Stack direction="row" sx={{ minWidth: 0, gap: 0.75, flexWrap: "wrap", alignItems: "baseline" }}>
          <Typography variant="body2" sx={{ fontWeight: "fontWeightBold" }}>
            <Highlighted text={question.text} question={question.path} field="question" />
          </Typography>
          <GroupChips groups={groups.get(question.path)} mine={myGroups} />
        </Stack>
        <Stack direction="row" sx={{ minWidth: 0, gap: 1, alignItems: "flex-start" }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>
              <Highlighted text={answerText(question, question.value)} question={question.path} field="answer" />
            </Typography>
            {/* Whether or not extracted answers are shown, one the protocol contradicts is said, leading to where */}
            {evidence && differs && (
              <Link
                component="button" variant="caption" underline="hover" onClick={() => onEvidence(evidence)}
                sx={{ display: "flex", alignItems: "center", gap: 0.5, color: "warning.main", mt: 0.25,
                  textAlign: "start" }}
              >
                <ErrorOutlineOutlined sx={{ fontSize: "1rem" }} />
                The protocol says {extracted}{evidence.passage?.page ? `, page ${evidence.passage.page}` : ""}
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
      {(side.length > 0 || writing) && (
        <Stack spacing={1} sx={{ minWidth: 0 }}>
          {side.map(note => (
            <SideCard key={note.id} note={note} kind={adopting === note.id ? "issue" : note.kind}
              onOpen={note.passage ? () => onEvidence(note) : undefined}>
              <NoteCard
                note={note} answerLabel={note.kind === "evidence" ? extracted : undefined}
                onReply={text => actions.write({ replyTo: note.id, text })}
                onEditReply={(reply, text) => actions.write({ edit: reply, text })}
                onRemoveReply={reply => actions.write({ remove: reply })}
                onEdit={(text, severity, topic) => actions.write({ edit: note.id, text, ...severity ? { severity } : {},
                  ...topic !== undefined ? { topic } : {} })}
                onRemove={() => actions.write({ remove: note.id })}
                onMakeIssue={(text, severity, topic) => actions.write(issueFrom(note, text, severity, topic))}
                onDismiss={() => actions.write({ dismiss: note.id })}
                adopting={adopting === note.id}
                onAdopting={on => setAdopting(on ? note.id : undefined)}
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
function SideCard({ note, kind, onOpen, children }: {
  note: ReviewNote;
  kind: NoteKind;
  onOpen?: () => void;
  children: ReactNode;
}) {
  const mark = kindInfo(kind).mark;
  return (
    <Box data-note={note.id} sx={theme => ({ ...passageCard(theme, mark), overflow: "hidden" })}>
      {note.passage && onOpen && (
        <ButtonBase
          onClick={onOpen}
          aria-label={`Show it in the document${note.passage.page ? `, page ${note.passage.page}` : ""}`}
          sx={theme => ({ display: "block", width: "100%", px: 1.5, py: 1, textAlign: "start",
            ...passageLayer(theme, mark),
            "@media (hover: hover)": { "&:hover": { bgcolor: passageTint(theme, mark, 0.5) } },
            "&.Mui-focusVisible": { bgcolor: passageTint(theme, mark, 0.5) } })}
        >
          <Typography variant="body2" component="span" sx={cutShort(3)}>“{note.passage.quote}”</Typography>
          <Typography variant="caption" component="span" sx={{ display: "flex", alignItems: "center", gap: 0.5,
            color: "text.secondary" }}>
            <ArticleOutlined sx={{ fontSize: "1rem" }} />
            In the protocol{note.passage.page ? `, page ${note.passage.page}` : ""}
          </Typography>
        </ButtonBase>
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
  notesWidth = 320, onNotesWidth }: {
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
}) {
  const home = homeOf ?? homes(items, data);
  const form = useRef<HTMLElement>(null);
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
              onEvidence={onEvidence} actions={actions} homeOf={home}
            />
          );
        }
        return (
          <Box key={item.name} sx={{ mt: 3 }}>
            <Typography variant="subheading" component={depth === 0 ? "h3" : "h4"}>{item.label}</Typography>
            <FormReview
              items={item.items} data={data} groups={groups} myGroups={myGroups} shown={shown}
              onEvidence={onEvidence} actions={actions} depth={depth + 1} homeOf={home}
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
  return (
    <Box ref={form} sx={{ containerType: "inline-size", position: "relative", [NOTES_WIDTH]: `${width}px` }}>
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
