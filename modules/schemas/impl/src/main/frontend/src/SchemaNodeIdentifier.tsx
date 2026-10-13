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

import CheckIcon from "@mui/icons-material/Check";
import CloseIcon from "@mui/icons-material/Close";
import EditOutlinedIcon from "@mui/icons-material/EditOutlined";
import { InputAdornment, Stack, TextField, Typography } from "@mui/material";

import { ActionIcon } from "@iap/frontend-commons/components/EventAction";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import { useAsyncAction } from "@iap/frontend-commons/useAsyncAction";

import CodePill from "./CodePill";

interface SchemaNodeIdentifierProps {
  name: string;
  // Renames the part, when it can be renamed. Resolves once the new name is taken.
  rename?: (name: string) => Promise<void>;
  // The workflow's rule for names, in words
  hint?: string;
}

// A field for a new identifier, with buttons to save or cancel. It shows why the last attempt was refused, if it was.
// Saving the current name sends nothing. Escape cancels the rename only, not the dialog around it.
function IdentifierEditor({ name, rename, hint, onDone }: {
  name: string;
  rename: (name: string) => Promise<void>;
  hint?: string;
  onDone: () => void;
}) {
  const [ value, setValue ] = useState(name);
  const { working, failure, run } = useAsyncAction<string>({ onFailure: messageOf, onSuccess: onDone });
  const input = useRef<HTMLInputElement>(null);
  // The field appears when Rename is pressed, so it takes the focus
  useEffect(() => input.current?.focus(), []);
  const confirm = () => (value.trim() === name ? onDone() : run(() => rename(value.trim())));
  return (
    <TextField
      label="Identifier"
      value={value}
      inputRef={input}
      disabled={working}
      error={Boolean(failure)}
      helperText={failure ?? hint}
      onChange={event => setValue(event.target.value)}
      onKeyDown={event => {
        if (event.key === "Enter") {
          confirm();
        } else if (event.key === "Escape") {
          event.stopPropagation();
          onDone();
        }
      }}
      slotProps={{ input: { endAdornment: (
        <InputAdornment position="end">
          <ActionIcon label="Save the identifier" icon={<CheckIcon fontSize="small" />} color="primary"
            onClick={confirm} />
          <ActionIcon label="Cancel renaming" icon={<CloseIcon fontSize="small" />} onClick={onDone} />
        </InputAdornment>
      ) } }}
    />
  );
}

// The identifier field for a new part. It shows the suggestion, which follows what the part says, until the user
// types another. Clearing the field brings the suggestion back.
export function NewIdentifier({ value, suggestion, hint, disabled, onChange }: {
  value?: string;
  suggestion: string;
  hint?: string;
  disabled: boolean;
  onChange: (value?: string) => void;
}) {
  return (
    <TextField
      label="Identifier"
      value={value ?? suggestion}
      disabled={disabled}
      helperText={hint}
      onChange={event => onChange(event.target.value === "" ? undefined : event.target.value)}
    />
  );
}

// A part's identifier, with a Rename button where the part can be renamed. A rename is sent on its own, apart from
// the fields the dialog saves.
function SchemaNodeIdentifier({ name, rename, hint }: SchemaNodeIdentifierProps) {
  const [ editing, setEditing ] = useState(false);
  if (editing && rename) {
    return <IdentifierEditor name={name} rename={rename} hint={hint} onDone={() => setEditing(false)} />;
  }
  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
      <Typography variant="description">Identifier</Typography>
      <CodePill name={name} />
      { rename && (
        <ActionIcon label="Rename" icon={<EditOutlinedIcon fontSize="small" />} onClick={() => setEditing(true)} />
      ) }
    </Stack>
  );
}

export default SchemaNodeIdentifier;
