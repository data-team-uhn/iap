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

import { useContext, useState, type ReactNode } from "react";

import EditOutlined from "@mui/icons-material/EditOutlined";
import { Box, Button, IconButton, Link, Stack, Tooltip, Typography } from "@mui/material";

import TagChip from "@iap/tags/TagChip";

import Composer from "./Composer";
import NoteKindIcon from "./noteIcons";
import { GroupChip, GroupLabels, PersonName } from "./people";
import RemoveButton from "./RemoveButton";
import { kindInfo, type ReviewNote } from "./reviewData";

import type { Message } from "./reviewModel";

function when(created: string): string {
  const date = new Date(created);
  return Number.isNaN(date.getTime()) ? "" : date.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

// When a note was written, to the minute, as a list of notes heads it
function whenExactly(created: string): string {
  const date = new Date(created);
  return Number.isNaN(date.getTime()) ? ""
    : date.toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
}

const BY_THE_AI = new Set<ReviewNote["kind"]>([ "aiIssue", "evidence", "contradiction" ]);

// A note's heading where a list heads each note, as beside a document: what kind it is, when it was written and by
// whom, where these say something; the AI's notes are its own and undated, and the reader's notes to self theirs
const headingIcon = (mark: string) => ({ fontSize: "0.9rem", color: `${mark}.main`, verticalAlign: "-0.15em", mr: 0.5 });

// The heading of a note being written, before it is any kind: a pencil, and what it is
export function DraftHeading() {
  return (
    <Typography variant="caption" component="div">
      <NoteKindIcon kind="draft" sx={headingIcon("draft")} />
      <Box component="span" sx={{ color: "draft.main", fontWeight: "fontWeightBold" }}>New note</Box>
    </Typography>
  );
}

export function NoteHeading({ note }: { note: ReviewNote }) {
  const info = kindInfo(note.kind);
  const byTheAi = BY_THE_AI.has(note.kind);
  const written = byTheAi ? "" : whenExactly(note.created);
  // Wrapped as words are, between the date and the name, each kept whole, and each dot with what comes before it,
  // so no line starts with one
  const dot = "\u00a0· ";
  return (
    <Typography variant="caption" component="div">
      <NoteKindIcon kind={note.kind} sx={headingIcon(info.mark)} />
      <Box component="span" sx={{ color: `${info.mark}.main`, fontWeight: "fontWeightBold" }}>{info.single}</Box>
      {written && <>{dot}<Box component="span" sx={{ whiteSpace: "nowrap" }}>{written}</Box></>}
      {!byTheAi && note.kind !== "note" && (
        <>{dot}<Box component="span" sx={{ whiteSpace: "nowrap" }}><PersonName user={note.author} /></Box></>
      )}
    </Typography>
  );
}

// One of a note's fields, named before its value, "Severity: Medium", the name quieter and smaller than the value. A
// value in words runs on after the name and wraps under it, as a paragraph would, the column being narrow
function Field({ label, chip = false, children }: { label: string; chip?: boolean; children: ReactNode }) {
  const name = <Box component="span" sx={{ typography: "caption", color: "text.secondary", mr: 0.75 }}>{label}:</Box>;
  if (chip) {
    return <Box sx={{ display: "inline-flex", alignItems: "center" }}>{name}{children}</Box>;
  }
  return <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>{name}{children}</Typography>;
}

// A question named on a note, leading to it in the form where there is somewhere to go
function QuestionLink({ text, onShow }: { text: string; onShow?: () => void }) {
  return onShow ? (
    // Underlined, as the review's tables link what an issue is about, and wrapping after its name as words do, which a
    // button cannot: a link acting as one
    <Link component="span" role="button" tabIndex={0} variant="body2" onClick={onShow}
      onKeyDown={event => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          onShow();
        }
      }}
      sx={{ cursor: "pointer" }}>
      {text}
    </Link>
  ) : text;
}

// Whose group raised an issue and how serious it is, each named, on one line as far as it goes
function Chips({ note }: { note: ReviewNote }) {
  const labels = useContext(GroupLabels);
  const group = note.group ? labels.get(note.group) : undefined;
  if (!group && !note.severity) {
    return null;
  }
  return (
    <Box sx={{ display: "flex", flexWrap: "wrap", columnGap: 2, rowGap: 0.5 }}>
      {group && <Field label="Raised by" chip><GroupChip label={group} mine={note.kind === "issue"} /></Field>}
      {note.severity && <Field label="Severity" chip><TagChip tags={[ note.severity ]} category="severity" /></Field>}
    </Box>
  );
}

// The buttons changing or removing one of the reader's own notes, at the end of the line heading it
export function NoteActions({ note, onEdit, onRemove }: {
  note: ReviewNote;
  onEdit?: () => void;
  onRemove?: () => Promise<void>;
}) {
  if (!note.mine || (!onEdit && !onRemove)) {
    return null;
  }
  return (
    <Box sx={{ ml: "auto", display: "flex", mr: -0.75 }}>
      {onEdit && (
        <Tooltip title="Change">
          <IconButton size="small" aria-label="Change" onClick={onEdit}>
            <EditOutlined fontSize="small" />
          </IconButton>
        </Tooltip>
      )}
      {onRemove && <RemoveButton what={WHAT[note.kind] ?? "note"} text={note.text} onRemove={onRemove} />}
    </Box>
  );
}

// What each kind of note the reviewer writes is called, where they are asked before removing one
const WHAT: Partial<Record<ReviewNote["kind"], string>> = { issue: "issue", discussion: "message", note: "note to self" };

// PRETOTYPE. One note as a card: what kind it is, who wrote it and when, and what it says; for an extracted
// answer, what the AI read and how sure it is. Discussion and issues can be answered; the reviewer's own can be
// changed or removed. An AI pre-review note can be made into an issue, worded and rated as the reviewer sees fit,
// or dismissed; whoever shows the card says whether it is being made into an issue, to colour it as one meanwhile.
export default function NoteCard({ note, answerLabel, onReply, onEdit, onRemove, onMakeIssue, onDismiss, adopting,
  onAdopting, onEditReply, onRemoveReply, headed = false, editing, onEditing, question, formAnswer, onShowAnswer }: {
  note: ReviewNote;
  // For a source or a contradiction shown away from its question, as beside a document, the question it is about
  question?: string;
  // For a contradiction, what the form answers, as its options call it
  formAnswer?: string;
  // For a linked answer or a contradiction shown beside a document, going to the question in the form, which its
  // question links to
  onShowAnswer?: () => void;
  // Whether the list showing it heads it already with what it is, when and by whom, and with the buttons
  // changing or removing it, which then say when it is being changed
  headed?: boolean;
  editing?: boolean;
  onEditing?: (editing: boolean) => void;
  // The extracted answer as the question's options call it
  answerLabel?: string;
  onReply?: (text: string) => Promise<void>;
  onEdit?: (text: string, severity?: string, topic?: string) => Promise<void>;
  onRemove?: () => Promise<void>;
  onMakeIssue?: (text: string, severity?: string, topic?: string) => Promise<void>;
  onDismiss?: () => Promise<void>;
  // Changing or removing one of the reader's own replies, by its path
  onEditReply?: (reply: string, text: string) => Promise<void>;
  onRemoveReply?: (reply: string) => Promise<void>;
  adopting?: boolean;
  onAdopting?: (adopting: boolean) => void;
}) {
  const info = kindInfo(note.kind);
  const [ replying, setReplying ] = useState(false);
  const [ ownEditing, setOwnEditing ] = useState(false);
  const isEditing = editing ?? ownEditing;
  const setEditing = onEditing ?? setOwnEditing;
  const [ ownAdopting, setOwnAdopting ] = useState(false);
  const isAdopting = adopting ?? ownAdopting;
  const setAdopting = onAdopting ?? setOwnAdopting;
  // The AI's comments, the reader's notes to self and discussion entries can become issues; an entry the reader
  // already raised is not offered again
  const canAdopt = onMakeIssue && (note.kind === "aiIssue" || (note.kind === "note" && note.mine)
    || (note.kind === "discussion" && !note.raisedByMe));
  const canAnswer = onReply && (note.kind === "discussion" || note.kind === "issue" || note.kind === "otherIssue");

  if (isEditing && onEdit) {
    return (
      <Composer
        kinds={[ note.kind ]} initialText={note.text} initialSeverity={note.severity}
        initialTopic={note.topicPath ?? ""} saveLabel="Save"
        onCancel={() => setEditing(false)}
        onSave={async (_kind, text, severity, topic) => {
          await onEdit(text, severity, topic);
          setEditing(false);
        }}
      />
    );
  }
  if (isAdopting && onMakeIssue) {
    return (
      <Composer
        kinds={[ "issue" ]} initialText={note.suggestion ? `${note.text}\n\n${note.suggestion}` : note.text}
        initialSeverity={note.severity} initialTopic={note.topicPath ?? ""} saveLabel="Add issue"
        onCancel={() => setAdopting(false)}
        onSave={async (_kind, text, severity, topic) => {
          await onMakeIssue(text, severity, topic);
          setAdopting(false);
        }}
      />
    );
  }
  return (
    <Box>
      {/* What it is, with the tools on the same line; under it the risk it is filed under, then who wrote it, for
          which group, and when */}
      {/* Headed, the heading says what it is, and holds the tools */}
      {!headed && (
        <Stack direction="row" sx={{ alignItems: "center", gap: 0.75, minHeight: 28 }}>
          {/* What the AI says carries its mark */}
          <NoteKindIcon kind={note.kind} sx={{ fontSize: "0.9rem", color: `${info.mark}.main`, mr: -0.25 }} />
          <Typography variant="caption" sx={{ color: `${info.mark}.main`, fontWeight: "fontWeightBold" }}>
            {info.single}
          </Typography>
          <NoteActions note={note} onEdit={onEdit && (() => setEditing(true))} onRemove={onRemove} />
        </Stack>
      )}
      {/* Each field named, so the chips and the risk are not left to be guessed */}
      <Stack sx={{ gap: 0.5, mb: 0.5 }}>
        <Chips note={note} />
        {note.topic && <Field label="Topic">{note.topic}</Field>}
      </Stack>
      {!headed && (
        <Typography variant="caption" sx={{ display: "block", color: "text.secondary", mb: 0.5 }}>
          {!BY_THE_AI.has(note.kind) && <><PersonName user={note.author} /> · </>}{when(note.created)}
        </Typography>
      )}
      {/* A linked answer says which answer it backs, and why the passage backs it, which is not always plain from the
          words; a contradiction, what the form says and what the protocol does, and why the AI read it so. The
          question leads to it in the form */}
      {note.kind === "evidence" && (
        <Stack sx={{ gap: 0.5 }}>
          {question && <Field label="Question"><QuestionLink text={question} onShow={onShowAnswer} /></Field>}
          <Field label="Answer"><b>{answerLabel ?? note.extracted}</b></Field>
          {note.reasoning && <Field label="Reasoning">{note.reasoning}</Field>}
        </Stack>
      )}
      {note.kind === "contradiction" && (
        <Stack sx={{ gap: 0.5 }}>
          {question && <Field label="Question"><QuestionLink text={question} onShow={onShowAnswer} /></Field>}
          {formAnswer && <Field label="Form says">{formAnswer}</Field>}
          <Field label="Protocol says"><b>{answerLabel ?? note.extracted}</b></Field>
          {note.reasoning && <Field label="Reasoning">{note.reasoning}</Field>}
        </Stack>
      )}
      {note.kind !== "evidence" && note.kind !== "contradiction" && (
        <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>{note.text}</Typography>
      )}
      {note.suggestion && (
        <Box sx={{ mt: 0.5 }}>
          <Field label="Suggestion">{note.suggestion}</Field>
        </Box>
      )}
      <Thread replies={note.replies} onEdit={onEditReply} onRemove={onRemoveReply} />
      {note.raisedBy && note.raisedBy.length > 0 && (
        <Typography variant="caption" sx={{ display: "block", color: "text.secondary", mt: 0.5 }}>
          Raised as an issue by {note.raisedBy.map((user, index) => (
            <span key={user}>{index > 0 ? ", " : ""}<PersonName user={user} /></span>
          ))}
        </Typography>
      )}
      {(Boolean(canAnswer && !replying) || Boolean(canAdopt)) && (
        <Stack direction="row" sx={{ ml: -0.75, mt: 0.25 }}>
          {canAnswer && !replying && (
            <Button size="small" variant="text" sx={{ minWidth: 0, px: 0.75 }}
              onClick={() => setReplying(true)}>Reply</Button>
          )}
          {canAdopt && (
            <Button size="small" variant="text" sx={{ minWidth: 0, px: 0.75 }}
              onClick={() => setAdopting(true)}>Make it an issue</Button>
          )}
          {canAdopt && onDismiss && note.kind === "aiIssue" && (
            <Button size="small" variant="text" sx={{ minWidth: 0, px: 0.75 }}
              onClick={() => void onDismiss()}>Dismiss</Button>
          )}
        </Stack>
      )}
      {canAnswer && replying && (
        <Box sx={{ mt: 1 }}>
          <Composer
            kinds={[]} saveLabel="Reply" onCancel={() => setReplying(false)}
            onSave={async (_kind, text) => {
              await onReply(text);
              setReplying(false);
            }}
          />
        </Box>
      )}
    </Box>
  );
}

// A note in brief, as a list shows one not selected: what kind it is and how serious, whose group raised an issue,
// how many replies it has, then its own words
export function NoteSummary({ note, headed = false }: { note: ReviewNote; headed?: boolean }) {
  const info = kindInfo(note.kind);
  return (
    <Box sx={{ px: 1.5, pb: 1 }}>
      <Stack direction="row" sx={{ alignItems: "center", gap: 0.75, minHeight: headed ? undefined : 24 }}>
        {!headed && <NoteKindIcon kind={note.kind} sx={{ fontSize: "0.9rem", color: `${info.mark}.main`, mr: -0.25 }} />}
        {!headed && (
          <Typography variant="caption" sx={{ color: `${info.mark}.main`, fontWeight: "fontWeightBold" }}>
            {info.single}
          </Typography>
        )}
        <Chips note={note} />
        {note.replies.length > 0 && (
          <Typography variant="caption" sx={{ color: "text.secondary", ml: "auto" }}>
            {note.replies.length === 1 ? "1 reply" : `${note.replies.length} replies`}
          </Typography>
        )}
      </Stack>
      <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>{note.text}</Typography>
    </Box>
  );
}

// The replies to a note, under it: who wrote each and when, and what it says; the reader's own can be changed or
// removed in place
export function Thread({ replies, onEdit, onRemove }: {
  replies: Message[];
  onEdit?: (reply: string, text: string) => Promise<void>;
  onRemove?: (reply: string) => Promise<void>;
}) {
  const [ editing, setEditing ] = useState<string>();
  if (replies.length === 0) {
    return null;
  }
  return (
    <Stack spacing={1} sx={{ mt: 1, pl: 1.5, borderInlineStart: 2, borderColor: "divider" }}>
      {replies.map(reply => (
        <Box key={reply.path}>
          <Stack direction="row" sx={{ alignItems: "center", minHeight: 26 }}>
            <Typography variant="caption" sx={{ color: "text.secondary", flex: 1 }}>
              <PersonName user={reply.author} /> · {when(reply.created)}
            </Typography>
            {reply.mine && onEdit && editing !== reply.path && (
              <Tooltip title="Change">
                <IconButton size="small" aria-label="Change the reply" onClick={() => setEditing(reply.path)}>
                  <EditOutlined fontSize="small" />
                </IconButton>
              </Tooltip>
            )}
            {reply.mine && onRemove && (
              <RemoveButton what="reply" text={reply.text} onRemove={() => onRemove(reply.path)} sx={{ mr: -0.75 }} />
            )}
          </Stack>
          {editing === reply.path && onEdit ? (
            <Composer
              kinds={[]} initialText={reply.text} saveLabel="Save" onCancel={() => setEditing(undefined)}
              onSave={async (_kind, text) => {
                await onEdit(reply.path, text);
                setEditing(undefined);
              }}
            />
          ) : (
            <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>{reply.text}</Typography>
          )}
        </Box>
      ))}
    </Stack>
  );
}
