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

import LockOutlinedIcon from "@mui/icons-material/LockOutlined";
import { Box, MenuItem, TextField, Tooltip, Typography } from "@mui/material";

import { choiceLabel, readOnlyReason, type Profile, type ProfileField } from "./profileApi";

interface Props {
  field: ProfileField;
  profile: Profile;
  value: string;
  // The reason the last save turned this field down, if it did
  error?: string;
  onChange: (value: string) => void;
}

// One field of a profile, rendered as whatever the catalogue says it is: a closed set of choices
// becomes a select, anything else a text box, and a field this person may not change becomes the
// value with a note saying who owns it. The catalogue drives the control, so a deployment adding a
// field gets one without any change here.
//
// A field the requester may not read is still shown — the catalogue is public, and a form that
// silently dropped a field would be lying about its own shape — but described as if nothing were
// recorded, because saying that there is a value would already be telling them something.
function ProfileFieldControl({ field, profile, value, error, onChange }: Props) {
  if (!field.readable) {
    return (
      <Withheld label={field.label} note="Not shown to you." />
    );
  }

  if (!field.editable) {
    return (
      <Withheld label={field.label} value={value} note={readOnlyReason(field, profile)} locked />
    );
  }

  const choices = field.allowedValues;
  return (
    <TextField
      select={Boolean(choices)}
      fullWidth
      size="small"
      label={field.label}
      value={value}
      onChange={event => onChange(event.target.value)}
      error={Boolean(error)}
      helperText={error ?? field.description}
      required={field.required}
      slotProps={{ htmlInput: field.pattern ? { pattern: field.pattern } : undefined }}
    >
      { /* An optional choice needs a way back to "nothing chosen"; a required one does not offer it */ }
      {choices && !field.required && <MenuItem value=""><em>No preference</em></MenuItem>}
      {choices?.map(choice => (
        <MenuItem key={choice} value={choice}>{choiceLabel(field, choice)}</MenuItem>
      ))}
    </TextField>
  );
}

// A field the person cannot change here, shown as a value rather than as a disabled control: a
// greyed-out input invites a click that does nothing, while a plain reading with a note says who
// owns the field and where to go instead.
function Withheld({ label, value = "", note, locked }: { label: string; value?: string; note: string; locked?: boolean }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" component="div">
        {label}
        {locked && (
          <Tooltip title={note}>
            <LockOutlinedIcon
              aria-label="Read-only"
              sx={{ fontSize: "0.875rem", verticalAlign: "text-bottom", ml: 0.5 }}
            />
          </Tooltip>
        )}
      </Typography>
      <Typography variant="body1" sx={{ minHeight: "1.5rem" }}>
        {value.length > 0 ? value : <Box component="span" sx={{ color: "text.disabled" }}>Not recorded</Box>}
      </Typography>
      <Typography variant="caption" color="text.secondary">{note}</Typography>
    </Box>
  );
}

export default ProfileFieldControl;
