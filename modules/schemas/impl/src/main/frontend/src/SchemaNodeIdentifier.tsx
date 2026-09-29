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

import { messageOf } from "@iap/frontend-commons/requestFailure";
import { useAsyncAction } from "@iap/frontend-commons/useAsyncAction";

import { ActionIcon } from "./EventAction";

interface SchemaNodeIdentifierProps {
  name: string;
  // How it is renamed, when it can be: resolves once the new name has been taken
  rename?: (name: string) => Promise<void>;
  // What a name may be, in words, as the workflow naming parts says
  hint?: string;
}

// Changing the identifier, confirmed or cancelled in place, with the refusal of the last attempt if there was one.
// Confirming the name it had changes nothing. Escape cancels only this, not the dialog around it.
function IdentifierEditor({ name, rename, hint, onDone }: {
  name: string;
  rename: (name: string) => Promise<void>;
  hint?: string;
  onDone: () => void;
}) {
  const [ value, setValue ] = useState(name);
  const { working, failure, run } = useAsyncAction<string>({ onFailure: messageOf, onSuccess: onDone });
  const input = useRef<HTMLInputElement>(null);
  // Asked for by pressing Rename, so the field it reveals is where the typing goes
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

// The identifier a new part is created with: the suggestion, following what the part says, until it is given one of
// its own; cleared, it follows again
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

// A part's identifier, set apart from the words around it
export function IdentifierPill({ name }: { name: string }) {
  return (
    <Typography variant="code" sx={{ bgcolor: "background.muted", px: 0.75, borderRadius: 1, overflowWrap: "anywhere" }}>
      {name}
    </Typography>
  );
}

// What names a part, shown as it is and, where the part can be renamed, changed as a step of its own, with its own
// outcome, apart from the fields saved with the rest.
function SchemaNodeIdentifier({ name, rename, hint }: SchemaNodeIdentifierProps) {
  const [ editing, setEditing ] = useState(false);
  if (editing && rename) {
    return <IdentifierEditor name={name} rename={rename} hint={hint} onDone={() => setEditing(false)} />;
  }
  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
      <Typography variant="description">Identifier</Typography>
      <IdentifierPill name={name} />
      { rename && (
        <ActionIcon label="Rename" icon={<EditOutlinedIcon fontSize="small" />} onClick={() => setEditing(true)} />
      ) }
    </Stack>
  );
}

export default SchemaNodeIdentifier;
