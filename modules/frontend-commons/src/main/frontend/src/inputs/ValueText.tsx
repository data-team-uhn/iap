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

import { Typography } from "@mui/material";

import { type Choice, choiceLabel } from "./choices";

const NOTHING = "—";

interface ValueTextProps {
  label: string;
  values: readonly string[];
  // Each value is shown by its choice's label if present
  choices?: readonly Choice[];
}

// Values shown as text under their label, for a form that cannot be edited.
export default function ValueText({ label, values, choices = [] }: ValueTextProps) {
  const shown = values.map(value => choiceLabel(choices, value));
  return <Typography>{label}: {shown.length === 0 ? NOTHING : shown.join(", ")}</Typography>;
}
