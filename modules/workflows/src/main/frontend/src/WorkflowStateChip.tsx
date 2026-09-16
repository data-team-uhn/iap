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

import { Chip, type ChipProps } from "@mui/material";

import { STATE_LABELS, type WorkflowState } from "./workflowModel";

// Active is the only state with a filled colour, since only one version of a workflow ever runs.
// Draft is unremarkable by design. Trial is coloured but outlined: it's being tried, not relied upon.
// Retired is a muted warning — still referenced, but no longer chosen.
const STATE_STYLES: Record<WorkflowState, Pick<ChipProps, "color" | "variant">> = {
  DRAFT: { color: "default", variant: "outlined" },
  TRIAL: { color: "info", variant: "outlined" },
  ACTIVE: { color: "success", variant: "filled" },
  RETIRED: { color: "warning", variant: "outlined" },
};

interface WorkflowStateChipProps {
  // Null when the stored state names no state this platform knows; see stateOf
  state: WorkflowState | null;
  size?: ChipProps["size"];
}

// Renders a version's lifecycle state so it reads the same everywhere it appears: the version table,
// the editor's header, an action's confirmation.
//
// A version with no readable state is drawn as an error rather than as one of the states: it can be
// neither edited nor promoted until it is re-authored, so naming it after any state would suggest
// actions that aren't there.
function WorkflowStateChip({ state, size = "small" }: WorkflowStateChipProps) {
  if (state === null) {
    return <Chip size={size} label="Unknown" color="error" variant="outlined" />;
  }
  return <Chip size={size} label={STATE_LABELS[state]} {...STATE_STYLES[state]} />;
}

export default WorkflowStateChip;
