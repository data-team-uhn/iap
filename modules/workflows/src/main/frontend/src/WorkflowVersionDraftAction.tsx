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

import ContentCopyOutlinedIcon from "@mui/icons-material/ContentCopyOutlined";
import { useNavigate } from "react-router";

import { ActionIcon } from "@iap/frontend-commons/components/EventAction";
import { offers } from "@iap/frontend-commons/workflowEvents";

import DraftFromVersionDialog from "./DraftFromVersionDialog";
import { adminUrl } from "./workflowModel";

import type { WorkflowVersionActionProps } from "./WorkflowVersionActions";

// Copies a version into a new draft: the copy is authored while the original keeps running, and takes
// over only once activated. Copying is a new version of the workflow, so it is offered wherever the
// workflow takes one, whatever the version being copied is: a version in no lifecycle has no other way
// forward, and a trial or a draft is branched from rather than changed.
function WorkflowVersionDraftAction({ version, workflow, reload }: WorkflowVersionActionProps) {
  const [ naming, setNaming ] = useState(false);
  const navigate = useNavigate();

  if (!offers(workflow, "createVersion")) {
    return null;
  }

  return (
    <>
      <ActionIcon label="New draft from this" icon={<ContentCopyOutlinedIcon fontSize="small" />}
        onClick={() => setNaming(true)} />
      { naming && (
        <DraftFromVersionDialog
          workflow={workflow}
          source={version}
          onClose={() => setNaming(false)}
          onCreated={created => {
            // Straight into the editor: a draft that was just copied exists to be changed
            reload();
            void navigate(adminUrl(created, "edit"));
          }}
        />
      ) }
    </>
  );
}

export default WorkflowVersionDraftAction;
