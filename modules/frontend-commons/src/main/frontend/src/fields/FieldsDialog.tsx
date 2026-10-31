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
  Alert, Button, DialogActions, DialogContent, FormControl, FormControlLabel, FormHelperText, Stack, Switch,
} from "@mui/material";

import ResponsiveDialog from "../components/ResponsiveDialog";
import ChoiceSelect from "../inputs/ChoiceSelect";
import MultiValueInput from "../inputs/MultiValueInput";
import NumberInput from "../inputs/NumberInput";
import TextInput from "../inputs/TextInput";
import { fromSingle, singleValue } from "../inputs/valueInput";
import { messageOf } from "../requestFailure";
import { useAsyncAction } from "../useAsyncAction";
import {
  applicableFields, blockingFields, changesOf, type ContentField, type FieldValue, fieldsOf, initialValues, isValid,
  type PatchValue, type SerializedNode,
} from "./fieldsModel";
import ReferenceInput from "./ReferenceInput";

interface FieldInputProps {
  field: ContentField;
  value: FieldValue;
  disabled: boolean;
  onChange: (value: FieldValue) => void;
}

function FieldInput({ field, value, disabled, onChange }: FieldInputProps) {
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
  const whole = field.kind === "long";
  const problems = isValid(field, value) ? [] : [ whole ? "Enter a whole number." : "Enter a number." ];
  const common = { label: field.label, description: field.help, required: field.mandatory, disabled, problems };
  if (Array.isArray(value)) {
    return <MultiValueInput {...common} choices={choices} values={value} onChange={onChange} />;
  }
  const single = { ...common, values: fromSingle(value), onChange: (next: string[]) => onChange(singleValue(next)) };
  if (choices.length > 0) {
    return <ChoiceSelect {...single} choices={choices} />;
  }
  if (field.kind === "text") {
    return <TextInput {...single} multiline={field.multiline} />;
  }
  return <NumberInput {...single} kind={whole ? "whole" : "decimal"} />;
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
  // Shown right after the first field, which names what is edited. It is given that field's value as it is
  // entered, and whether a save is under way.
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
          loading={working}
          disabled={working || blocked || Object.keys(changes).length === 0}
          onClick={() => run(() => onSave(changes))}
        >
          { working ? "Saving…" : "Save" }
        </Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}

export default FieldsDialog;
