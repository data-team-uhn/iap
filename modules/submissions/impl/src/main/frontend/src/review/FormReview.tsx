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

import { useState } from "react";

import AddCommentOutlined from "@mui/icons-material/AddCommentOutlined";
import ArticleOutlined from "@mui/icons-material/ArticleOutlined";
import ErrorOutlineOutlined from "@mui/icons-material/ErrorOutlineOutlined";
import { Box, Button, Chip, Stack, Typography } from "@mui/material";

import { type FormItem, type FormQuestion, isQuestion } from "../submissionForm";
import Composer, { WRITTEN } from "./Composer";
import NoteCard from "./NoteCard";
import { groupShortName, kindInfo, notesAbout, type NoteKind, type ReviewData, type ReviewGroup,
  type ReviewNote } from "./reviewData";

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

function GroupChips({ groups, mine }: { groups: ReviewGroup[] | undefined; mine: string[] }) {
  if (!groups) {
    return null;
  }
  return (
    <Stack direction="row" sx={{ gap: 0.5, flexWrap: "wrap" }}>
      {groups.map(group => (
        <Chip
          key={group.name} size="small" variant="outlined" label={groupShortName(group.label)}
          color={mine.includes(group.group) ? "primary" : "default"}
          sx={{ height: 20, fontSize: "caption.fontSize" }}
        />
      ))}
    </Stack>
  );
}

// PRETOTYPE. One question, compact: what was asked, what was answered, which groups asked it, where the answer
// was found in the protocol, and everything written about it.
function QuestionReview({ question, data, groups, myGroups, shown, faded, onEvidence, actions }: {
  question: FormQuestion;
  data: ReviewData;
  groups: Map<string, ReviewGroup[]>;
  myGroups: string[];
  shown: Set<NoteKind>;
  faded: boolean;
  onEvidence: (note: ReviewNote) => void;
  actions: ReviewActions;
}) {
  const [ writing, setWriting ] = useState(false);
  const answer = data.answers.get(question.path);
  const notes = answer ? notesAbout(data.notes, [ answer.uuid ]) : [];
  const evidence = notes.find(note => note.kind === "evidence");
  const differs = disagrees(question, evidence?.extracted);
  const others = notes.filter(note => note.kind !== "evidence" && shown.has(note.kind));
  return (
    <Box
      id={`question-${question.path}`}
      sx={{ py: 1.5, borderBottom: 1, borderColor: "divider", opacity: faded ? 0.55 : 1, scrollMarginTop: 96 }}
    >
      <Stack direction={{ xs: "column", md: "row" }} sx={{ gap: { xs: 0.5, md: 2 } }}>
        <Box sx={{ flex: "1 1 0", minWidth: 0 }}>
          <Typography variant="body2" sx={{ color: "text.secondary" }}>{question.text}</Typography>
          <GroupChips groups={groups.get(question.path)} mine={myGroups} />
        </Box>
        <Box sx={{ flex: "1 1 0", minWidth: 0 }}>
          <Typography variant="body2" sx={{ whiteSpace: "pre-wrap", fontWeight: "fontWeightMedium" }}>
            {answerText(question, question.value)}
          </Typography>
          <Stack direction="row" sx={{ alignItems: "center", gap: 1, flexWrap: "wrap", mt: 0.5 }}>
            {evidence && shown.has("evidence") && (
              <Button
                size="small" color="evidence" startIcon={<ArticleOutlined />} sx={{ ml: -0.5 }}
                onClick={() => onEvidence(evidence)}
              >
                In the protocol{evidence.passage?.page ? `, page ${evidence.passage.page}` : ""}
              </Button>
            )}
            {differs && shown.has("evidence") && (
              <Stack direction="row" sx={{ alignItems: "center", gap: 0.5, color: "warning.main" }}>
                <ErrorOutlineOutlined fontSize="small" />
                <Typography variant="caption" sx={{ color: "warning.main" }}>
                  The protocol says {answerText(question, evidence?.extracted?.split(";").map(one => one.trim()) ?? [])}
                </Typography>
              </Stack>
            )}
            {!writing && (
              <Button
                size="small" startIcon={<AddCommentOutlined />} sx={{ ml: "auto" }} onClick={() => setWriting(true)}
                aria-label={`Write about: ${question.text}`}
              >
                Write
              </Button>
            )}
          </Stack>
        </Box>
      </Stack>
      {(others.length > 0 || writing) && (
        <Stack spacing={1} sx={{ mt: 1, ml: { md: "50%" } }}>
          {others.map(note => (
            <Box key={note.id} sx={theme => ({ p: 1, borderRadius: 1, borderInlineStart: 3,
              borderColor: theme.palette[kindInfo(note.kind).mark].main, bgcolor: "background.muted" })}
            >
              <NoteCard
                note={note}
                onReply={text => actions.write({ replyTo: note.id, text })}
                onEdit={(text, severity) => actions.write({ edit: note.id, text, ...severity ? { severity } : {} })}
                onRemove={() => actions.write({ remove: note.id })}
              />
            </Box>
          ))}
          {writing && answer && (
            <Composer
              kinds={WRITTEN} initialKind="issue"
              onCancel={() => setWriting(false)}
              onSave={async (kind, text, severity) => {
                await actions.write({ kind, text, subjects: [ answer.uuid ], ...severity ? { severity } : {} });
                setWriting(false);
              }}
            />
          )}
        </Stack>
      )}
    </Box>
  );
}

// Whether anything in these items is shown
function anyShown(items: FormItem[], mine: (path: string) => boolean, whole: boolean): boolean {
  return items.some(item => isQuestion(item) ? whole || mine(item.path) : anyShown(item.items, mine, whole));
}

// The questions of a form requirement, section by section. Focused on the reviewer's own, the others are left
// out; looking at the whole submission, they are faded.
export default function FormReview({ items, data, groups, myGroups, isMine, whole, shown, onEvidence, actions,
  depth = 0 }: {
  items: FormItem[];
  data: ReviewData;
  groups: Map<string, ReviewGroup[]>;
  myGroups: string[];
  // Whether a question is the reviewer's to look at
  isMine: (path: string) => boolean;
  whole: boolean;
  shown: Set<NoteKind>;
  onEvidence: (note: ReviewNote) => void;
  actions: ReviewActions;
  depth?: number;
}) {
  return (
    <>
      {items.map(item => {
        if (isQuestion(item)) {
          const mine = isMine(item.path);
          return mine || whole ? (
            <QuestionReview
              key={item.path} question={item} data={data} groups={groups} myGroups={myGroups} shown={shown}
              faded={!mine} onEvidence={onEvidence} actions={actions}
            />
          ) : null;
        }
        return anyShown(item.items, isMine, whole) && (
          <Box key={item.name} sx={{ mt: 2 }}>
            <Typography variant="subheading" component={depth === 0 ? "h3" : "h4"}>{item.label}</Typography>
            <FormReview
              items={item.items} data={data} groups={groups} myGroups={myGroups} isMine={isMine} whole={whole}
              shown={shown} onEvidence={onEvidence} actions={actions} depth={depth + 1}
            />
          </Box>
        );
      })}
    </>
  );
}
