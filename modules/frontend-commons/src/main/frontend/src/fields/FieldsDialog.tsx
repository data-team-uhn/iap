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

import { Fragment, useState, type ReactNode } from "react";

import {
  Alert, Autocomplete, Button, DialogActions, DialogContent, FormControl, FormControlLabel, FormHelperText, MenuItem,
  Stack, Switch, TextField,
} from "@mui/material";

import ResponsiveDialog from "../components/ResponsiveDialog";
import { messageOf } from "../requestFailure";
import { useAsyncAction } from "../useAsyncAction";
import {
  applicableFields, blockingFields, changesOf, type ContentField, type FieldValue, fieldsOf, initialValues, isValid,
  type PatchValue, type SerializedNode,
} from "./fieldsModel";
import ReferenceInput from "./ReferenceInput";

const INVALID: Partial<Record<ContentField["kind"], string>> = {
  long: "Enter a whole number.",
  double: "Enter a number.",
};

const INPUT_MODES: Partial<Record<ContentField["kind"], "numeric" | "decimal">> = {
  long: "numeric",
  double: "decimal",
};

interface FieldInputProps {
  field: ContentField;
  value: FieldValue;
  disabled: boolean;
  onChange: (value: FieldValue) => void;
}

function FieldInput({ field, value, disabled, onChange }: FieldInputProps) {
  const invalid = !isValid(field, value);
  const helperText = invalid ? INVALID[field.kind] : field.help;
  const common = { label: field.label, required: field.mandatory, disabled, error: invalid, helperText };

  if (typeof value === "boolean") {
    return (
      <FormControl disabled={disabled}>
        <FormControlLabel
          label={field.label}
          control={<Switch checked={value} onChange={event => onChange(event.target.checked)} />}
        />
        { field.help && <FormHelperText>{field.help}</FormHelperText> }
      </FormControl>
    );
  }
  if (field.kind === "reference") {
    return (
      <ReferenceInput field={field} value={value} disabled={disabled} helperText={field.help} onChange={onChange} />
    );
  }
  const choices = field.choices ?? [];
  if (Array.isArray(value)) {
    // Several values: picked among the choices when there are some, else typed in one at a time
    const labelOf = (option: string) => choices.find(choice => choice.value === option)?.label ?? option;
    return (
      <Autocomplete<string, true, false, boolean>
        multiple
        freeSolo={choices.length === 0}
        autoSelect={choices.length === 0}
        options={choices.map(choice => choice.value)}
        getOptionLabel={labelOf}
        value={value}
        disabled={disabled}
        onChange={(_event, picked) => onChange(picked)}
        renderInput={params => <TextField {...params} {...common} />}
      />
    );
  }
  if (choices.length > 0) {
    // A value no longer among the choices is still shown for what it is
    const offered = choices.some(choice => choice.value === value) || value === ""
      ? choices : [ ...choices, { value, label: value } ];
    return (
      <TextField select {...common} value={value} onChange={event => onChange(event.target.value)}>
        { !field.mandatory && <MenuItem value=""><em>None</em></MenuItem> }
        { offered.map(choice => <MenuItem key={choice.value} value={choice.value}>{choice.label}</MenuItem>) }
      </TextField>
    );
  }
  return (
    <TextField
      {...common}
      value={value}
      multiline={field.multiline}
      minRows={field.multiline ? 2 : undefined}
      slotProps={{ htmlInput: { inputMode: INPUT_MODES[field.kind] ?? "text" } }}
      onChange={event => onChange(event.target.value)}
    />
  );
}

interface FieldsDialogProps {
  title: string;
  // The content being edited, serialized with the fields its update would change
  node: SerializedNode;
  onClose: () => void;
  // Given only the fields that changed; an emptied one is null, which removes it
  onSave: (changes: Record<string, PatchValue>) => Promise<unknown>;
  // Anything the caller asks along with the fields, shown above them
  children?: ReactNode;
  // Anything that belongs with the first field, which names what is edited, shown right after it and given what it
  // holds as it is entered, and whether a save is under way
  afterFirstField?: (value: FieldValue, working: boolean) => ReactNode;
}

// Edits what an update event lets change on a node, as its `@fields` describe: each field with an input
// for its kind, and only while it applies to what is being entered.
function FieldsDialog({ title, node, onClose, onSave, children, afterFirstField }: FieldsDialogProps) {
  const fields = fieldsOf(node);
  const [ values, setValues ] = useState(() => initialValues(node, fields));
  const { working, failure, run } = useAsyncAction<string>({ onFailure: messageOf, onSuccess: onClose });

  const changes = changesOf(fields, values, node);
  const blocked = blockingFields(fields, values, node).length > 0;

  return (
    <ResponsiveDialog title={title} withCloseButton open onClose={onClose} closeDisabled={working}>
      <DialogContent dividers>
        <Stack spacing={2}>
          {children}
          { applicableFields(fields, values, node).map((field, at) => (
            <Fragment key={field.name}>
              <FieldInput
                field={field}
                value={values[field.name]}
                disabled={working}
                onChange={value => setValues(current => ({ ...current, [field.name]: value }))}
              />
              { at === 0 && afterFirstField?.(values[field.name], working) }
            </Fragment>
          )) }
          { failure && <Alert severity="error">{failure}</Alert> }
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={working}>Cancel</Button>
        <Button
          variant="contained"
          disabled={working || blocked || Object.keys(changes).length === 0}
          onClick={() => run(() => onSave(changes))}
        >
          Save
        </Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}

export default FieldsDialog;
