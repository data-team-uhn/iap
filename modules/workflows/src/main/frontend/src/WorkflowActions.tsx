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

import ActionBar from "@iap/frontend-commons/components/ActionBar";

import type { WorkflowSummary } from "./workflowModel";

// A module contributes an action on a workflow by shipping an `ext:Extension` on this point.
// The workflow's page never learns of it.
export const WORKFLOW_ACTIONS_POINT = "WorkflowActions";

// Props every action receives. An action that navigates away or changes nothing need not reload.
export interface WorkflowActionProps {
  workflow: WorkflowSummary;
  reload: () => void;
}

function WorkflowActions(props: WorkflowActionProps) {
  return <ActionBar point={WORKFLOW_ACTIONS_POINT} {...props} />;
}

export default WorkflowActions;
