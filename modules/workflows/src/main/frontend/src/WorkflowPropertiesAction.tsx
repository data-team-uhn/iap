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

import { useState } from "react";

import DriveFileRenameOutlineIcon from "@mui/icons-material/DriveFileRenameOutline";

import { ActionIcon } from "@iap/frontend-commons/components/EventAction";
import { offers } from "@iap/frontend-commons/workflowEvents";

import WorkflowPropertiesDialog from "./WorkflowPropertiesDialog";

import type { WorkflowActionProps } from "./WorkflowActions";

// Edits what a workflow says about itself, as its own `save` event.
function WorkflowPropertiesAction({ workflow, reload }: WorkflowActionProps) {
  const [ editing, setEditing ] = useState(false);
  if (!offers(workflow, "save")) {
    return null;
  }
  return (
    <>
      <ActionIcon label="Edit properties" icon={<DriveFileRenameOutlineIcon fontSize="small" />} onClick={() => setEditing(true)} />
      { editing && (
        <WorkflowPropertiesDialog workflow={workflow} onClose={() => setEditing(false)} onSaved={reload} />
      ) }
    </>
  );
}

export default WorkflowPropertiesAction;
