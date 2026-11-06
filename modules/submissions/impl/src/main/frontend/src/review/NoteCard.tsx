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

import DeleteOutlined from "@mui/icons-material/DeleteOutlined";
import EditOutlined from "@mui/icons-material/EditOutlined";
import { Box, Button, IconButton, Stack, Tooltip, Typography } from "@mui/material";

import TagChip from "@iap/tags/TagChip";

import Composer from "./Composer";
import { GroupChip, GroupLabels, PersonName } from "./people";
import { kindInfo, type ReviewNote } from "./reviewData";

function when(created: string): string {
  const date = new Date(created);
  return Number.isNaN(date.getTime()) ? "" : date.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

// PRETOTYPE. One note as a card: what kind it is, who wrote it and when, and what it says; for an extracted
// answer, what the AI read and how sure it is. Discussion and issues can be answered; the reviewer's own can be
// changed or removed. An AI pre-review note can be made into an issue, worded and rated as the reviewer sees fit,
// or dismissed; whoever shows the card says whether it is being made into an issue, to colour it as one meanwhile.
export default function NoteCard({ note, answerLabel, onReply, onEdit, onRemove, onMakeIssue, onDismiss, adopting,
  onAdopting }: {
  note: ReviewNote;
  // The extracted answer as the question's options call it
  answerLabel?: string;
  onReply?: (text: string) => Promise<void>;
  onEdit?: (text: string, severity?: string, topic?: string) => Promise<void>;
  onRemove?: () => Promise<void>;
  onMakeIssue?: (text: string, severity?: string, topic?: string) => Promise<void>;
  onDismiss?: () => Promise<void>;
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
      <Stack direction="row" sx={{ alignItems: "center", gap: 0.75, minHeight: 28 }}>
        <Typography variant="caption" sx={{ color: `${info.mark}.main`, fontWeight: "fontWeightBold" }}>
          {info.single}
        </Typography>
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
            <Tooltip title="Remove">
              <IconButton size="small" aria-label="Remove" onClick={() => void onRemove()}>
                <DeleteOutlined fontSize="small" />
              </IconButton>
            </Tooltip>
          )}
        </Box>
      </Stack>
      {note.topic && (
        <Typography variant="caption" sx={{ display: "block", color: "text.secondary", fontStyle: "italic" }}>
          {note.topic}
        </Typography>
      )}
      <Typography variant="caption" sx={{ display: "block", color: "text.secondary", mb: 0.5 }}>
        {note.kind !== "evidence" && <><PersonName user={note.author} /> · </>}{when(note.created)}
      </Typography>
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
      {note.replies.length > 0 && (
        <Stack spacing={1} sx={{ mt: 1, pl: 1.5, borderLeft: 2, borderColor: "divider" }}>
          {note.replies.map(reply => (
            <Box key={reply.path}>
              <Typography variant="caption" sx={{ color: "text.secondary" }}>
                <PersonName user={reply.author} />, {when(reply.created)}
              </Typography>
              <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>{reply.text}</Typography>
            </Box>
          ))}
        </Stack>
      )}
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
