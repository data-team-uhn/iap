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

import ScienceIcon from "@mui/icons-material/Science";

import { EventAction } from "@iap/frontend-commons/components/EventAction";

import { offers } from "./workflowModel";

import type { WorkflowVersionActionProps } from "./WorkflowVersionActions";

// Puts a draft on trial: the diagram is frozen, as it is for anything past drafting, but the version
// is not yet the one new instances are created from.
function WorkflowVersionTrialAction({ version, workflow, reload }: WorkflowVersionActionProps) {
  if (!offers(version, "startTrial")) {
    return null;
  }
  const label = version.version || version.name;
  return (
    <EventAction
      path={version.path}
      reload={reload}
      icon={<ScienceIcon fontSize="small" />}
      label="Start trial"
      event="startTrial"
      title={`Put version ${label} on trial?`}
      explanation={`Version ${label} stops being editable: a trial is tried as it stands. It does not become the`
        + ` version new instances of ${workflow.title} are created from — activating it is a separate step, and`
        + " returning it to being a draft is how it is changed again."}
      done={`Version ${label} of ${workflow.title} is on trial`}
    />
  );
}

export default WorkflowVersionTrialAction;
