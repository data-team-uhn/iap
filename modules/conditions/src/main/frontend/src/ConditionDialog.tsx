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

import { Alert, Box, Button, DialogActions, DialogContent, LinearProgress, Stack } from "@mui/material";

import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import type { SerializedNode } from "@iap/frontend-commons/serializedNode";
import { useAsyncAction } from "@iap/frontend-commons/useAsyncAction";

import AppliesWhenLine from "./AppliesWhenLine";
import ConditionBuilder from "./ConditionBuilder";
import { contentOf, draftOf, fingerprintOf, isComplete, whenDraftApplies } from "./conditionModel";
import { useConditionTypes } from "./conditionTypes";

interface ConditionDialogProps {
  title: string;
  // The condition as stored, if there is one
  condition?: SerializedNode;
  // The content the condition is on, which decides what it can name
  path: string;
  onClose: () => void;
  // Given the condition to write, or null to remove it
  onSave: (condition: SerializedNode | null) => Promise<unknown>;
}

// Edits a condition whole, saying as it changes when what it guards will apply
function ConditionDialog({ title, condition, path, onClose, onSave }: ConditionDialogProps) {
  const types = useConditionTypes(path);
  const [ draft, setDraft ] = useState(() => draftOf(condition));
  const [ initial ] = useState(() => fingerprintOf(draft));
  const { working, failure, run } = useAsyncAction<string>({ onFailure: messageOf, onSuccess: onClose });
  const [ clearing, setClearing ] = useState(false);

  const complete = types !== undefined && isComplete(draft, types);
  const content = types && contentOf(draft, types);
  const changed = fingerprintOf(draft) !== initial;

  return (
    <ResponsiveDialog title={title} width="md" withCloseButton open onClose={onClose} closeDisabled={working}>
      <DialogContent dividers>
        <Stack spacing={2}>
          <Box aria-live="polite">
            { complete && draft.conditions.length > 0 && (
              <AppliesWhenLine>{whenDraftApplies(draft, types) ?? "Always applies."}</AppliesWhenLine>
            ) }
          </Box>
          { types
            ? <ConditionBuilder draft={draft} onChange={setDraft} types={types} disabled={working} />
            : <LinearProgress aria-label="Loading what conditions can say" /> }
          { failure && <Alert severity="error">{failure}</Alert> }
        </Stack>
      </DialogContent>
      <DialogActions>
        { condition && (
          <Button variant="text" color="error" disabled={working} loading={working && clearing} sx={{ mr: "auto" }}
            onClick={() => {
              setClearing(true);
              run(() => onSave(null));
            }}>
            { working && clearing ? "Clearing…" : "Clear all conditions" }
          </Button>
        ) }
        <Button onClick={onClose} disabled={working}>Cancel</Button>
        <Button variant="contained" disabled={working || !complete || !changed} loading={working && !clearing}
          onClick={() => {
            setClearing(false);
            run(() => onSave(content ?? null));
          }}>
          { working && !clearing ? "Saving…" : "Save" }
        </Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}

export default ConditionDialog;
