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

import VisibilityIcon from "@mui/icons-material/Visibility";

import { ActionIcon } from "@iap/frontend-commons/components/EventAction";

import { adminUrl } from "./workflowModel";

import type { WorkflowVersionActionProps } from "./WorkflowVersionActions";

// Opens a version's diagram in the editor's read-only mode. Offered for every state, since viewing a
// workflow is never a change to it.
function WorkflowVersionViewAction({ version }: WorkflowVersionActionProps) {
  return <ActionIcon label="View" icon={<VisibilityIcon fontSize="small" />} to={adminUrl(version.path)} />;
}

export default WorkflowVersionViewAction;
