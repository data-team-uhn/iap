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

import { ListSubheader, MenuItem, Stack, TextField } from "@mui/material";

import LifecycleChip from "./LifecycleChip";
import { type JcrNode, labelOf, pathOf, tagsOf, titleOf, versionsOf } from "./schemaModel";

interface SchemaVersionPickerProps {
  schemas: JcrNode[];
  // The path of the version picked, or empty for none
  value: string;
  onChange: (path: string) => void;
  // Whether the versions are listed under their schemas' titles
  grouped?: boolean;
  disabled?: boolean;
}

// Which version a new one starts as a copy of, if any.
function SchemaVersionPicker({ schemas, value, onChange, grouped = false, disabled }: SchemaVersionPickerProps) {
  const choices = schemas.flatMap(schema => versionsOf(schema).map(version => ({ schema, version })));
  const named = (path: string) => {
    const choice = choices.find(({ version }) => pathOf(version) === path);
    if (!choice) {
      return "An empty version";
    }
    const copied = `A copy of version ${labelOf(choice.version)}`;
    return grouped ? `${copied} of ${titleOf(choice.schema)}` : copied;
  };
  return (
    <TextField
      select
      label="Start from"
      value={value}
      disabled={disabled}
      onChange={event => onChange(event.target.value)}
      slotProps={{
        select: { displayEmpty: true, renderValue: selected => named(String(selected)) },
        inputLabel: { shrink: true },
      }}
    >
      <MenuItem value="">An empty version</MenuItem>
      { schemas.flatMap(schema => [
        ...grouped ? [ <ListSubheader key={`${pathOf(schema)}#title`}>{titleOf(schema)}</ListSubheader> ] : [],
        ...versionsOf(schema).map(version => (
          <MenuItem key={pathOf(version)} value={pathOf(version)}>
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <span>{`A copy of version ${labelOf(version)}`}</span>
              <LifecycleChip tags={tagsOf(version)} />
            </Stack>
          </MenuItem>
        )),
      ]) }
    </TextField>
  );
}

export default SchemaVersionPicker;
