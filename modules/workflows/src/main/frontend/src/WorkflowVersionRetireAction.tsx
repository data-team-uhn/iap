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

import ArchiveOutlinedIcon from "@mui/icons-material/ArchiveOutlined";

import { EventAction } from "@iap/frontend-commons/components/EventAction";
import { offers } from "@iap/frontend-commons/workflowEvents";

import type { WorkflowVersionActionProps } from "./WorkflowVersionActions";

// Withdraws the active version without promoting another in its place, which leaves the workflow
// retired until one of its versions is activated again.
function WorkflowVersionRetireAction({ version, workflow, reload }: WorkflowVersionActionProps) {
  if (!offers(version, "retire")) {
    return null;
  }
  const label = version.version || version.name;
  return (
    <EventAction
      path={version.path}
      reload={reload}
      icon={<ArchiveOutlinedIcon fontSize="small" />}
      label="Retire"
      event="retire"
      color="warning"
      title={`Retire version ${label}?`}
      explanation={`No new instances of ${workflow.title} can be started until a version is activated again. The`
        + ` instances already running against version ${label} carry on.`}
      done={`Version ${label} is retired, and ${workflow.title} has no active version`}
    />
  );
}

export default WorkflowVersionRetireAction;
