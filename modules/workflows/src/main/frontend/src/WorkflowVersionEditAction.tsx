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

import EditIcon from "@mui/icons-material/Edit";

import { ActionIcon } from "@iap/frontend-commons/components/EventAction";
import { offers } from "@iap/frontend-commons/workflowEvents";

import { adminUrl } from "./workflowModel";

import type { WorkflowVersionActionProps } from "./WorkflowVersionActions";

// Opens a draft's diagram in the editor, which saves it as the version's own `save` event.
function WorkflowVersionEditAction({ version }: WorkflowVersionActionProps) {
  if (!offers(version, "save")) {
    return null;
  }
  return <ActionIcon label="Edit" icon={<EditIcon fontSize="small" />} to={adminUrl(version.path, "edit")} />;
}

export default WorkflowVersionEditAction;
