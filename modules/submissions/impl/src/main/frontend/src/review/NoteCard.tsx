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

import { useContext, useState } from "react";

import AutoAwesomeOutlined from "@mui/icons-material/AutoAwesomeOutlined";
import EditOutlined from "@mui/icons-material/EditOutlined";
import { Box, Button, IconButton, Stack, Tooltip, Typography } from "@mui/material";

import TagChip from "@iap/tags/TagChip";

import Composer from "./Composer";
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

const BY_THE_AI = new Set<ReviewNote["kind"]>([ "aiIssue", "evidence" ]);

// A note's heading where a list heads each note, as beside a document: what kind it is, when it was written and by
// whom, where these say something; the AI's notes are its own and undated, and the reader's notes to self theirs
export function NoteHeading({ note }: { note: ReviewNote }) {
  const info = kindInfo(note.kind);
  const byTheAi = BY_THE_AI.has(note.kind);
  const written = byTheAi ? "" : whenExactly(note.created);
  return (
    <Stack direction="row" sx={{ alignItems: "center", gap: 0.5, flexWrap: "wrap", minWidth: 0 }}>
      {byTheAi && <AutoAwesomeOutlined sx={{ fontSize: "0.9rem", color: `${info.mark}.main` }} />}
      <Typography variant="caption" sx={{ color: `${info.mark}.main`, fontWeight: "fontWeightBold" }}>
        {info.single}
      </Typography>
      {written && <Typography variant="caption">· {written}</Typography>}
      {!byTheAi && note.kind !== "note" && (
        <Typography variant="caption">· <PersonName user={note.author} /></Typography>
      )}
    </Stack>
  );
}

// What each kind of note the reviewer writes is called, where they are asked before removing one
const WHAT: Partial<Record<ReviewNote["kind"], string>> = { issue: "issue", discussion: "message", note: "note to self" };

// PRETOTYPE. One note as a card: what kind it is, who wrote it and when, and what it says; for an extracted
// answer, what the AI read and how sure it is. Discussion and issues can be answered; the reviewer's own can be
// changed or removed. An AI pre-review note can be made into an issue, worded and rated as the reviewer sees fit,
// or dismissed; whoever shows the card says whether it is being made into an issue, to colour it as one meanwhile.
export default function NoteCard({ note, answerLabel, onReply, onEdit, onRemove, onMakeIssue, onDismiss, adopting,
  onAdopting, onEditReply, onRemoveReply, headed = false }: {
  note: ReviewNote;
  // Whether the list showing it heads it already with what it is, when and by whom
  headed?: boolean;
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
  const labels = useContext(GroupLabels);
  const [ replying, setReplying ] = useState(false);
  const [ editing, setEditing ] = useState(false);
  const [ ownAdopting, setOwnAdopting ] = useState(false);
  const isAdopting = adopting ?? ownAdopting;
  const setAdopting = onAdopting ?? setOwnAdopting;
  const canAdopt = onMakeIssue && note.kind === "aiIssue";
  const canAnswer = onReply && (note.kind === "discussion" || note.kind === "issue" || note.kind === "otherIssue");

  if (editing && onEdit) {
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
      <Stack direction="row" sx={{ alignItems: "center", gap: 0.75, minHeight: headed ? undefined : 28 }}>
        {/* What the AI says carries its mark */}
        {!headed && (note.kind === "aiIssue" || note.kind === "evidence") && (
          <AutoAwesomeOutlined sx={{ fontSize: "0.9rem", color: `${info.mark}.main`, mr: -0.25 }} />
        )}
        {!headed && (
          <Typography variant="caption" sx={{ color: `${info.mark}.main`, fontWeight: "fontWeightBold" }}>
            {info.single}
          </Typography>
        )}
        {/* An issue says whose group raised it, the reviewer's own as it is marked everywhere */}
        {note.group && labels.has(note.group) && (
          <GroupChip label={labels.get(note.group) ?? ""} mine={note.kind === "issue"} />
        )}
        {note.severity && <TagChip tags={[ note.severity ]} category="severity" />}
        <Box sx={{ ml: "auto", display: "flex", mr: -0.75 }}>
          {note.mine && onEdit && (
            <Tooltip title="Change">
              <IconButton size="small" aria-label="Change" onClick={() => setEditing(true)}>
                <EditOutlined fontSize="small" />
              </IconButton>
            </Tooltip>
          )}
          {note.mine && onRemove && (
            <RemoveButton what={WHAT[note.kind] ?? "note"} text={note.text} onRemove={onRemove} />
          )}
        </Box>
      </Stack>
      {note.topic && (
        <Typography variant="caption" sx={{ display: "block", color: "text.secondary", fontStyle: "italic" }}>
          {note.topic}
        </Typography>
      )}
      {!headed && (
        <Typography variant="caption" sx={{ display: "block", color: "text.secondary", mb: 0.5 }}>
          {note.kind !== "evidence" && <><PersonName user={note.author} /> · </>}{when(note.created)}
        </Typography>
      )}
      {note.kind === "evidence" ? (
        <>
          <Typography variant="body2"><b>{answerLabel ?? note.extracted}</b></Typography>
          <Typography variant="body2" sx={{ color: "text.secondary" }}>
            {note.reasoning}
            {note.confidence !== undefined ? ` Confidence ${Math.round(note.confidence * 100)}%.` : ""}
          </Typography>
        </>
      ) : (
        <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>{note.text}</Typography>
      )}
      {note.suggestion && (
        <Typography variant="body2" sx={{ color: "text.secondary", mt: 0.5 }}>Suggestion. {note.suggestion}</Typography>
      )}
      <Thread replies={note.replies} onEdit={onEditReply} onRemove={onRemoveReply} />
      {canAdopt && (
        <Stack direction="row" sx={{ ml: -0.75, mt: 0.25 }}>
          <Button size="small" variant="text" sx={{ minWidth: 0, px: 0.75 }}
            onClick={() => setAdopting(true)}>Make it an issue</Button>
          {onDismiss && (
            <Button size="small" variant="text" sx={{ minWidth: 0, px: 0.75 }}
              onClick={() => void onDismiss()}>Dismiss</Button>
          )}
        </Stack>
      )}
      {canAnswer && !replying && (
        <Button size="small" variant="text" sx={{ ml: -0.75, mt: 0.25, minWidth: 0, px: 0.75 }}
          onClick={() => setReplying(true)}>Reply</Button>
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
  const labels = useContext(GroupLabels);
  return (
    <Box sx={{ px: 1.5, pb: 1 }}>
      <Stack direction="row" sx={{ alignItems: "center", gap: 0.75, minHeight: headed ? undefined : 24 }}>
        {!headed && note.kind === "aiIssue" && (
          <AutoAwesomeOutlined sx={{ fontSize: "0.9rem", color: `${info.mark}.main`, mr: -0.25 }} />
        )}
        {!headed && (
          <Typography variant="caption" sx={{ color: `${info.mark}.main`, fontWeight: "fontWeightBold" }}>
            {info.single}
          </Typography>
        )}
        {note.group && labels.has(note.group) && (
          <GroupChip label={labels.get(note.group) ?? ""} mine={note.kind === "issue"} />
        )}
        {note.severity && <TagChip tags={[ note.severity ]} category="severity" />}
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
