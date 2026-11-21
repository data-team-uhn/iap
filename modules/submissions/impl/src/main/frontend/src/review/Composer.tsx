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

import { createContext, useContext, useEffect, useRef, useState } from "react";

import CheckCircleOutlined from "@mui/icons-material/CheckCircleOutlined";
import CircleOutlined from "@mui/icons-material/CircleOutlined";
import { Button, Chip, Divider, MenuItem, Stack, TextField, type Theme } from "@mui/material";

import type { PassageKind } from "@iap/documents-pdf-viewer/marksModel";
import { chipStyle } from "@iap/frontend-commons/chipStyle";
import { loadTagDefinitions, type TagDefinition } from "@iap/tags/tagDefinitions";

import { kindInfo, SEVERITIES, type NoteKind, type Topic } from "./reviewData";

// A chip for a kind of note chosen, styled as a tag of its colour is: a soft tint, a readable shade of the colour for
// the text, a border mixing the two; kept under the pointer. Outlined like an unchosen one, so it keeps its size.
export function kindChip(theme: Theme, mark: PassageKind) {
  const style = chipStyle(theme, theme.palette[mark].light);
  const border = style?.border?.replace(/^1px solid /, "");
  return { ...style, border: undefined, borderColor: border, "&:hover": { backgroundColor: style?.backgroundColor } };
}

// What a reviewer can write themselves
export const WRITTEN: NoteKind[] = [ "issue", "discussion", "note" ];

// The risks the reviewer's groups weigh, in the schema's order, which an issue can be filed under; and every risk, to
// name one that is not theirs
export const ReviewerTopics = createContext<{ mine: Topic[]; all: Map<string, Topic> }>({ mine: [], all: new Map() });

// PRETOTYPE. Writing a note: which kind, for an issue how serious and under which risk, and what it says. The risk
// is chosen for the reviewer when only one of theirs draws on what the note is about.
export default function Composer({ kinds, initialKind, initialText = "", initialSeverity, initialTopic, about = [],
  saveLabel = "Add", onSave, onCancel }: {
  // The kinds to choose between; none for a reply
  kinds: NoteKind[];
  initialKind?: NoteKind;
  initialText?: string;
  initialSeverity?: string;
  // The risk to start with, by path; empty for none
  initialTopic?: string;
  // What the note is about: questions and documents, by schema path
  about?: string[];
  saveLabel?: string;
  // The topic is empty for none, and undefined unless the note is an issue
  onSave: (kind: NoteKind, text: string, severity?: string, topic?: string) => Promise<void>;
  onCancel: () => void;
}) {
  const [ kind, setKind ] = useState<NoteKind>(initialKind ?? kinds.at(0) ?? "discussion");
  const [ text, setText ] = useState(initialText);
  const [ severity, setSeverity ] = useState(initialSeverity ?? "medium");
  const { mine, all } = useContext(ReviewerTopics);
  const fitting = mine.filter(topic => topic.sources.some(source => about.includes(source)));
  const [ topic, setTopic ] = useState(initialTopic ?? (fitting.length === 1 ? fitting[0].path : ""));
  // Those drawing on what the note is about first; and the one it starts with, even when not the reviewer's own
  const others = mine.filter(one => !fitting.includes(one));
  const outside = topic && !mine.some(one => one.path === topic) ? topic : undefined;
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
      await onSave(kind, text.trim(), kind === "issue" ? severity : undefined, kind === "issue" ? topic : undefined);
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
            const { mark, choice } = kindInfo(one);
            return (
              <Chip
                key={one} size="small" label={choice} role="radio" aria-checked={kind === one}
                icon={kind === one ? <CheckCircleOutlined /> : <CircleOutlined />}
                variant="outlined" onClick={() => setKind(one)}
                // The chosen one cannot be unchosen, so it does not answer the pointer
                sx={theme => ({ "& .MuiChip-icon": { color: "inherit" },
                  ...kind === one ? { ...kindChip(theme, mark), cursor: "default" }
                    : { borderColor: kindChip(theme, mark).borderColor } })}
              />
            );
          })}
        </Stack>
      )}
      {kind === "issue" && (
        <Stack direction="row" sx={{ gap: 1, flexWrap: "wrap" }}>
          <TextField
            select size="small" label="Severity" value={severity} onChange={event => setSeverity(event.target.value)}
            sx={{ width: 150 }}
          >
            {SEVERITIES.map(one => (
              <MenuItem key={one} value={one}>{severities.find(tag => tag.name === one)?.label ?? one}</MenuItem>
            ))}
          </TextField>
          <TextField
            select size="small" label="Topic" value={topic} onChange={event => setTopic(event.target.value)}
            sx={{ flex: 1, minWidth: 200 }}
            slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}
          >
            <MenuItem value=""><em>None</em></MenuItem>
            {outside && <MenuItem value={outside}>{all.get(outside)?.label ?? outside.split("/").at(-1)}</MenuItem>}
            {fitting.map(one => <MenuItem key={one.path} value={one.path}>{one.label}</MenuItem>)}
            {fitting.length > 0 && others.length > 0 && <Divider />}
            {others.map(one => <MenuItem key={one.path} value={one.path}>{one.label}</MenuItem>)}
          </TextField>
        </Stack>
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
