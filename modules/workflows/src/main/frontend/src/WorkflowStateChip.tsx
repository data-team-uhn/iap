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

// How each state is coloured. Only one version of a workflow is the one that runs, so active is the
// only state given a filled colour; a draft is unremarkable by design, a trial is coloured but left
// outlined — something is being tried, not relied upon — and retired is the muted warning that
// something is still referenced but no longer chosen.
const STATE_STYLES: Record<WorkflowState, Pick<ChipProps, "color" | "variant">> = {
  DRAFT: { color: "default", variant: "outlined" },
  TRIAL: { color: "info", variant: "outlined" },
  ACTIVE: { color: "success", variant: "filled" },
  RETIRED: { color: "warning", variant: "outlined" },
};

interface WorkflowStateChipProps {
  state: WorkflowState;
  size?: ChipProps["size"];
}

// A workflow version's lifecycle state, as a chip. One component so the states read the same
// wherever they appear: in the version table, on the editor's header, on an action's confirmation.
function WorkflowStateChip({ state, size = "small" }: WorkflowStateChipProps) {
  return <Chip size={size} label={STATE_LABELS[state]} {...STATE_STYLES[state]} />;
}

export default WorkflowStateChip;
