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

import CloseOutlined from "@mui/icons-material/CloseOutlined";
import { Box, Button, Drawer, IconButton, Stack, TextField, Typography } from "@mui/material";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { PersonName } from "./people";
import { writeComment } from "./reviewApi";

import type { Message } from "./reviewModel";

function when(created: string): string {
  const date = new Date(created);
  return Number.isNaN(date.getTime()) ? "" : date.toLocaleString(undefined,
    { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
}

function Entry({ message, onReply }: { message: Message; onReply?: () => void }) {
  return (
    <Box>
      <Stack direction="row" sx={{ alignItems: "baseline", gap: 1 }}>
        <Typography variant="subtitle2"><PersonName user={message.author} /></Typography>
        <Typography variant="caption" sx={{ color: "text.secondary" }}>{when(message.created)}</Typography>
      </Stack>
      <Typography variant="body2" sx={{ whiteSpace: "pre-wrap" }}>{message.text}</Typography>
      {onReply && <Button size="small" onClick={onReply} sx={{ ml: -1 }}>Reply</Button>}
    </Box>
  );
}

// PRETOTYPE. The reviewers' conversation on one submission, beside whatever the reviewer was looking at: what
// they used email for.
export default function DiscussionPanel({ open, path, title, messages, onChanged, onClose }: {
  open: boolean;
  path: string;
  title: string;
  messages: Message[];
  onChanged: () => Promise<void>;
  onClose: () => void;
}) {
  const doFetch = useAuthenticatedFetch();
  const [ draft, setDraft ] = useState("");
  const [ replyTo, setReplyTo ] = useState<Message>();
  const [ sending, setSending ] = useState(false);

  const send = async () => {
    const text = draft.trim();
    if (!text) {
      return;
    }
    setSending(true);
    try {
      await writeComment(doFetch, path, replyTo ? { replyTo: replyTo.path, text } : { kind: "discussion", text });
      setDraft("");
      setReplyTo(undefined);
      await onChanged();
    } finally {
      setSending(false);
    }
  };

  return (
    <Drawer
      anchor="right" open={open} onClose={onClose}
      onClick={event => event.stopPropagation()}
      slotProps={{ paper: { sx: { width: { xs: "100%", sm: 440 }, display: "flex" } } }}
    >
      <Stack direction="row" sx={{ alignItems: "flex-start", gap: 1, p: 2, pb: 1 }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography variant="h6">Discussion</Typography>
          <Typography variant="body2" sx={{ color: "text.secondary" }}>{title}</Typography>
        </Box>
        <IconButton aria-label="Close" onClick={onClose}><CloseOutlined /></IconButton>
      </Stack>
      <Stack spacing={2} sx={{ flex: 1, overflowY: "auto", px: 2, py: 1 }}>
        {messages.length === 0 && (
          <Typography variant="body2" sx={{ color: "text.secondary" }}>
            Nobody has written yet. Only reviewers see what is written here.
          </Typography>
        )}
        {messages.map(message => (
          <Box key={message.path}>
            <Entry message={message} onReply={() => setReplyTo(message)} />
            <Stack spacing={1.5} sx={{ pl: 2, mt: 1, borderLeft: 2, borderColor: "divider" }}>
              {message.replies.map(reply => <Entry key={reply.path} message={reply} />)}
            </Stack>
          </Box>
        ))}
      </Stack>
      <Box sx={{ p: 2, borderTop: 1, borderColor: "divider" }}>
        {replyTo && (
          <Stack direction="row" sx={{ alignItems: "center", gap: 1, mb: 1 }}>
            <Typography variant="caption" sx={{ flex: 1, color: "text.secondary", minWidth: 0, overflow: "hidden",
              textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
              Replying to <PersonName user={replyTo.author} />. {replyTo.text}
            </Typography>
            <Button size="small" onClick={() => setReplyTo(undefined)}>Cancel</Button>
          </Stack>
        )}
        <TextField
          multiline fullWidth minRows={2} maxRows={6} value={draft}
          placeholder={replyTo ? "Your reply" : "Write to the other reviewers"}
          onChange={event => setDraft(event.target.value)}
          onKeyDown={event => {
            if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
              void send();
            }
          }}
          slotProps={{ htmlInput: { "aria-label": replyTo ? "Your reply" : "Your message" } }}
        />
        <Stack direction="row" sx={{ justifyContent: "flex-end", mt: 1 }}>
          <Button variant="contained" disabled={sending || !draft.trim()} onClick={() => void send()}>Send</Button>
        </Stack>
      </Box>
    </Drawer>
  );
}
