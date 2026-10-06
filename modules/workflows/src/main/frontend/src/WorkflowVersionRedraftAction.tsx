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

import UndoIcon from "@mui/icons-material/Undo";

import { EventAction } from "@iap/frontend-commons/components/EventAction";
import { offers } from "@iap/frontend-commons/workflowEvents";

import type { WorkflowVersionActionProps } from "./WorkflowVersionActions";

// Takes a trial back to draft, so the same node becomes editable again rather than a copy being
// drafted beside it.
function WorkflowVersionRedraftAction({ version, workflow, reload }: WorkflowVersionActionProps) {
  if (!offers(version, "returnToDraft")) {
    return null;
  }
  const label = version.version || version.name;
  return (
    <EventAction
      path={version.path}
      reload={reload}
      icon={<UndoIcon fontSize="small" />}
      label="Return to draft"
      event="returnToDraft"
      title={`Return version ${label} to draft?`}
      explanation={`The trial of version ${label} ends and its process becomes editable again. Nothing else about`
        + ` ${workflow.title} changes: whichever version was active stays active.`}
      done={`Version ${label} of ${workflow.title} is a draft again`}
    />
  );
}

export default WorkflowVersionRedraftAction;
