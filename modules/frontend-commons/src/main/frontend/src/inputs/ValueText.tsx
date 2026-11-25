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

// Shows the values of a field, where a form is read but not edited.

import { Stack, Typography } from "@mui/material";

import { type Choice, choiceLabel } from "./choices";
import CodePill from "../components/CodePill";

const NOTHING = "―";

interface ValueTextProps {
  label: string;
  values: readonly string[];
  // Each value is shown by the label of the choice holding it, or as itself when it is not one of the offered choices
  choices?: readonly Choice[];
  // Shows each value in the code style, for an identifier
  code?: boolean;
}

// Values shown under their label, for a form that cannot be edited.
export default function ValueText({ label, values, choices = [], code = false }: ValueTextProps) {
  const shown = values.map(value => choiceLabel(choices, value));
  return (
    <Stack>
      <Typography variant="description">{label}</Typography>
      {
        shown.length === 0
          ? <Typography sx={{ color: "text.disabled" }}>{NOTHING}</Typography>
          : code
            ? <Stack direction="row" spacing={1}>{shown.map(value => <CodePill key={value} name={value} />)}</Stack>
            : <Typography>{shown.join(", ")}</Typography>
      }
    </Stack>
  );
}
