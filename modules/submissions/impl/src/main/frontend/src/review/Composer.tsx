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

import { useEffect, useRef, useState } from "react";

import { Button, Chip, MenuItem, Stack, TextField } from "@mui/material";

import { loadTagDefinitions, type TagDefinition } from "@iap/tags/tagDefinitions";

import { kindInfo, SEVERITIES, type NoteKind } from "./reviewData";

// What a reviewer can write themselves
export const WRITTEN: NoteKind[] = [ "issue", "discussion", "note" ];

// PRETOTYPE. Writing a note: which kind, for an issue how serious, and what it says.
export default function Composer({ kinds, initialKind, initialText = "", initialSeverity, saveLabel = "Add",
  onSave, onCancel }: {
  // The kinds to choose between; none for a reply
  kinds: NoteKind[];
  initialKind?: NoteKind;
  initialText?: string;
  initialSeverity?: string;
  saveLabel?: string;
  onSave: (kind: NoteKind, text: string, severity?: string) => Promise<void>;
  onCancel: () => void;
}) {
  const [ kind, setKind ] = useState<NoteKind>(initialKind ?? kinds.at(0) ?? "discussion");
  const [ text, setText ] = useState(initialText);
  const [ severity, setSeverity ] = useState(initialSeverity ?? "medium");
  const [ saving, setSaving ] = useState(false);
  const field = useRef<HTMLTextAreaElement>(null);
  const [ severities, setSeverities ] = useState<TagDefinition[]>([]);
  useEffect(() => {
    void loadTagDefinitions("severity").then(setSeverities);
  }, []);
  // Into the field as soon as it opens, since opening it is asking to write
  useEffect(() => {
    field.current?.focus();
  }, []);

  const save = async () => {
    if (!text.trim()) {
      return;
    }
    setSaving(true);
    try {
      await onSave(kind, text.trim(), kind === "issue" ? severity : undefined);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Stack spacing={1} onClick={event => event.stopPropagation()}>
      {/* Chips in the colours of the kinds, as the note filters are, the chosen one filled */}
      {kinds.length > 1 && (
        <Stack direction="row" role="radiogroup" aria-label="What to write" sx={{ gap: 0.75, flexWrap: "wrap" }}>
          {kinds.map(one => {
            const { mark, single } = kindInfo(one);
            return (
              <Chip
                key={one} size="small" label={single} role="radio" aria-checked={kind === one}
                variant={kind === one ? "filled" : "outlined"} onClick={() => setKind(one)}
                // The chosen one cannot be unchosen, so it does not answer the pointer
                sx={theme => {
                  const fill = theme.alpha(theme.palette[mark].light, 0.45);
                  return {
                    borderColor: theme.palette[mark].main,
                    ...kind === one ? { bgcolor: fill, cursor: "default", "&:hover": { bgcolor: fill } } : {},
                  };
                }}
              />
            );
          })}
        </Stack>
      )}
      {kind === "issue" && (
        <TextField
          select size="small" label="Severity" value={severity} onChange={event => setSeverity(event.target.value)}
          sx={{ maxWidth: 220 }}
        >
          {SEVERITIES.map(one => (
            <MenuItem key={one} value={one}>{severities.find(tag => tag.name === one)?.label ?? one}</MenuItem>
          ))}
        </TextField>
      )}
      <TextField
        inputRef={field} multiline minRows={2} maxRows={8} fullWidth size="small" value={text}
        placeholder={kinds.length === 0 ? "Your reply" : kind === "issue" ? "What the study team needs to address"
          : kind === "discussion" ? "What you want to ask or tell the other reviewers" : "A note only you will see"}
        onChange={event => setText(event.target.value)}
        onKeyDown={event => {
          event.stopPropagation();
          if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
            void save();
          } else if (event.key === "Escape") {
            onCancel();
          }
        }}
        slotProps={{ htmlInput: { "aria-label": "What to write" } }}
      />
      <Stack direction="row" spacing={1} sx={{ justifyContent: "flex-end" }}>
        <Button size="small" onClick={onCancel}>Cancel</Button>
        <Button size="small" variant="contained" disabled={saving || !text.trim()} onClick={() => void save()}>
          {saveLabel}
        </Button>
      </Stack>
    </Stack>
  );
}
