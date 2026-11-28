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
// PRETOTYPE. What the study team sees on sending a submission the AI has read: its findings, each to fix, to answer
// with why they leave it, or to leave as it is, before they send.

import { createContext, useState } from "react";

import AutoAwesomeOutlined from "@mui/icons-material/AutoAwesomeOutlined";
import CheckCircleOutline from "@mui/icons-material/CheckCircleOutlineOutlined";
import EditOutlined from "@mui/icons-material/EditOutlined";
import ReplyOutlined from "@mui/icons-material/ReplyOutlined";
import SendOutlined from "@mui/icons-material/SendOutlined";
import {
  Box,
  Button,
  DialogActions,
  DialogContent,
  Paper,
  Stack,
  TextField,
  Typography,
} from "@mui/material";

import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import TagChip from "@iap/tags/TagChip";

import { type AiFinding, type AiPreReview, findingCounts } from "./aiFindings";

// The finding the team went to fix, and the question it points them to, for the editor to show it there
export interface FindingFocus {
  finding: AiFinding;
  question?: string;
  // Opens the findings again
  onBack: () => void;
}

export const FindingFocusContext = createContext<FindingFocus | undefined>(undefined);

export function FindingSeverity({ severity }: { severity?: string }) {
  if (!severity) {
    return null;
  }
  return severity === "suggestion"
    ? <TagChip tags={[ severity ]} category="issue-kind" />
    : <TagChip tags={[ severity ]} category="severity" />;
}

// What the AI said, and what it suggests
export function FindingText({ finding }: { finding: AiFinding }) {
  return (
    <>
      <Typography variant="body2">{finding.text}</Typography>
      {finding.suggestion && (
        <Typography variant="body2" sx={{ color: "text.secondary" }}>
          <Box component="span" sx={{ fontWeight: "fontWeightMedium" }}>Suggestion </Box>{finding.suggestion}
        </Typography>
      )}
    </>
  );
}

function FindingCard({ finding, onFix, onRespond }: {
  finding: AiFinding;
  onFix: (finding: AiFinding) => void;
  onRespond: (finding: AiFinding, text: string) => Promise<void>;
}) {
  const [ draft, setDraft ] = useState<string>();
  const [ saving, setSaving ] = useState(false);
  const [ failure, setFailure ] = useState<string>();
  const save = (text: string) => {
    setSaving(true);
    setFailure(undefined);
    onRespond(finding, text)
      .then(() => setDraft(undefined))
      .catch((error: unknown) => setFailure(messageOf(error)))
      .finally(() => setSaving(false));
  };
  const about = finding.subjects.map(subject => `“${subject.label}”`).join(", ");
  return (
    <Paper variant="outlined" sx={{ p: 1.5, borderInlineStart: "3px solid", borderInlineStartColor: "assistant.main" }}>
      <Stack spacing={1}>
        <Stack direction="row" sx={{ alignItems: "baseline", gap: 1, flexWrap: "wrap" }}>
          <FindingSeverity severity={finding.severity} />
          {about && <Typography variant="caption" sx={{ color: "text.secondary" }}>About {about}</Typography>}
        </Stack>
        <FindingText finding={finding} />
        {finding.changed && (
          <Stack direction="row" spacing={0.5} sx={{ alignItems: "flex-start", color: "success.main" }}>
            <CheckCircleOutline fontSize="small" />
            <Typography variant="body2">
              You changed what this is about. The AI reads your submission again when you send it.
            </Typography>
          </Stack>
        )}
        {draft === undefined && finding.response && (
          <Box sx={{ bgcolor: "action.hover", borderRadius: 1, px: 1.5, py: 1 }}>
            <Typography variant="caption" sx={{ color: "text.secondary" }}>Your response, for the reviewers</Typography>
            <Typography variant="body2">{finding.response}</Typography>
          </Box>
        )}
        {draft !== undefined && (
          <TextField multiline minRows={2} size="small" value={draft} disabled={saving}
            label="Why you're leaving it as it is" helperText={failure ?? "The reviewers will read this."}
            error={Boolean(failure)} onChange={event => setDraft(event.target.value)} />
        )}
        <Stack direction="row" spacing={1} sx={{ justifyContent: "flex-end" }}>
          {draft !== undefined ? (
            <>
              <Button size="small" disabled={saving} onClick={() => setDraft(undefined)}>Cancel</Button>
              <Button size="small" variant="contained" disabled={saving || !draft.trim()}
                onClick={() => save(draft.trim())}>
                Save
              </Button>
            </>
          ) : (
            <>
              {finding.response && (
                <Button size="small" color="inherit" disabled={saving} onClick={() => save("")}>Remove</Button>
              )}
              <Button size="small" startIcon={<EditOutlined />} onClick={() => onFix(finding)}>Fix</Button>
              <Button size="small" startIcon={<ReplyOutlined />} onClick={() => setDraft(finding.response ?? "")}>
                {finding.response ? "Edit response" : "Respond"}
              </Button>
            </>
          )}
        </Stack>
      </Stack>
    </Paper>
  );
}

export default function PreSendDialog({ review, open, onFix, onRespond, onSend, onClose }: {
  review: AiPreReview;
  open: boolean;
  onFix: (finding: AiFinding) => void;
  onRespond: (finding: AiFinding, text: string) => Promise<void>;
  onSend: () => void;
  onClose: () => void;
}) {
  const topics = [ ...new Set(review.findings.map(finding => finding.topic)) ];
  const { changed, responded, left } = findingCounts(review.findings);
  const counts = [ changed && `${changed} changed`, responded && `${responded} responded to`,
    left && `${left} left as ${left === 1 ? "it is" : "they are"}` ].filter(Boolean).join(", ");
  return (
    <ResponsiveDialog open={open} width="md" withCloseButton onClose={onClose}
      title={(
        <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
          <AutoAwesomeOutlined sx={{ color: "assistant.main" }} />
          <span>Before you send</span>
        </Stack>
      )}>
      <DialogContent dividers>
        <Stack spacing={2}>
          <Typography variant="body2">
            {`The AI read your submission the way the reviewers will, and has ${review.findings.length} ${
              review.findings.length === 1 ? "finding" : "findings"}. Fix what you agree with, or tell the reviewers `
              + "why you're leaving it. You can also send it as it is. The AI reads it again when you send it, and the "
              + "reviewers see what it still finds, with your responses."}
          </Typography>
          {review.summary && (
            <Typography variant="body2" sx={{ color: "text.secondary" }}>{review.summary}</Typography>
          )}
          {topics.map(topic => (
            <Stack key={topic} spacing={1}>
              <Typography variant="subtitle2" component="h3">{topic}</Typography>
              {review.findings.filter(finding => finding.topic === topic).map(finding => (
                <FindingCard key={finding.id} finding={finding} onFix={onFix} onRespond={onRespond} />
              ))}
            </Stack>
          ))}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ flexWrap: "wrap", gap: 1 }}>
        <Typography variant="body2" sx={{ flex: 1, color: "text.secondary", pl: 1 }}>{counts}</Typography>
        <Button onClick={onClose}>Keep editing</Button>
        <Button variant="contained" startIcon={<SendOutlined />} onClick={onSend}>Send</Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}
