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

import {
  Alert,
  Box,
  Button,
  Checkbox,
  DialogActions,
  DialogContent,
  FormControlLabel,
  Paper,
  Stack,
  TextField,
  Typography,
} from "@mui/material";

import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";
import TagChip from "@iap/tags/TagChip";

import { leadIn } from "./rebLetter";

import type { ReviewNote } from "./reviewData";

export interface BoardPart {
  name: string;
  label: string;
}

// PRETOTYPE. Escalating a study to the full board: choose what the board needs, and see exactly what the members
// would see through the personal link they are sent. Sending is not built yet.
export default function EscalateDialog({ open, title, identifier, pi, summary, parts, issues, about, onClose }: {
  open: boolean;
  title: string;
  identifier: string;
  pi: string;
  summary: string;
  parts: BoardPart[];
  issues: ReviewNote[];
  about: (note: ReviewNote) => string;
  onClose: () => void;
}) {
  const [ chosen, setChosen ] = useState(() => new Set(parts.map(part => part.name)));
  const [ shared, setShared ] = useState(() => new Set(issues.filter(issue => [ "critical", "major" ]
    .includes(issue.severity ?? "")).map(issue => issue.id)));
  const [ message, setMessage ] = useState("The REB reviewers would like the board's decision on the points below.");
  const [ sent, setSent ] = useState(false);
  const toggle = (set: Set<string>, value: string) => {
    const next = new Set(set);
    if (next.has(value)) {
      next.delete(value);
    } else {
      next.add(value);
    }
    return next;
  };
  return (
    <ResponsiveDialog open={open} onClose={onClose} title="Escalate to the board" width="lg" withCloseButton>
      <DialogContent>
        <Stack direction={{ xs: "column", md: "row" }} spacing={3}>
          <Stack spacing={1.5} sx={{ flex: 1, minWidth: 0 }}>
            <Typography variant="subtitle2">What the board receives</Typography>
            <TextField
              multiline minRows={3} label="Message" value={message} onChange={event => setMessage(event.target.value)}
            />
            <Typography variant="subheading">Parts of the submission</Typography>
            {parts.map(part => (
              <FormControlLabel
                key={part.name} label={part.label}
                control={<Checkbox checked={chosen.has(part.name)}
                  onChange={() => setChosen(current => toggle(current, part.name))} />}
              />
            ))}
            <Typography variant="subheading">Issues</Typography>
            {issues.map(issue => (
              <FormControlLabel
                key={issue.id}
                label={<Typography variant="body2">{leadIn(about(issue) || "General")}{issue.text}</Typography>}
                control={<Checkbox checked={shared.has(issue.id)}
                  onChange={() => setShared(current => toggle(current, issue.id))} />}
              />
            ))}
          </Stack>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography variant="subtitle2" sx={{ mb: 1 }}>What a board member sees</Typography>
            <Paper variant="outlined" sx={{ p: 2, bgcolor: "background.muted" }}>
              <Typography variant="h6">{title}</Typography>
              <Typography variant="body2" sx={{ color: "text.secondary" }}>{identifier} · {pi}</Typography>
              <Typography variant="body2" sx={{ mt: 1.5, whiteSpace: "pre-wrap" }}>{message}</Typography>
              <Typography variant="body2" sx={{ mt: 1.5 }}>{summary}</Typography>
              <Typography variant="subheading" sx={{ mt: 2, display: "block" }}>The points to decide</Typography>
              {issues.filter(issue => shared.has(issue.id)).map(issue => (
                <Box key={issue.id} sx={{ mt: 1 }}>
                  {issue.severity && <TagChip tags={[ issue.severity ]} category="severity" />}
                  <Typography variant="body2"><b>{leadIn(about(issue) || "General")}</b>{issue.text}</Typography>
                </Box>
              ))}
              <Typography variant="subheading" sx={{ mt: 2, display: "block" }}>Can be opened</Typography>
              <Typography variant="body2">
                {parts.filter(part => chosen.has(part.name)).map(part => part.label).join(", ") || "Nothing else"}
              </Typography>
            </Paper>
          </Box>
        </Stack>
        {sent && (
          <Alert severity="info" sx={{ mt: 2 }}>
            In the real platform, each board member would now get an email with a personal link to exactly this.
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" onClick={() => setSent(true)}>Send to the board</Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}
