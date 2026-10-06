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

import PublishIcon from "@mui/icons-material/Publish";

import { EventAction } from "@iap/frontend-commons/components/EventAction";

import { ACTIVE_TAG, offers } from "./workflowModel";

import type { WorkflowVersionActionProps } from "./WorkflowVersionActions";

// Promotes a draft, a version that has been on trial, or a retired version being brought back, to the
// version new instances are created from.
//
// The confirmation names the version this retires, which the row the action sits in does not show.
function WorkflowVersionActivateAction({ version, workflow, reload }: WorkflowVersionActionProps) {
  if (!offers(version, "activate")) {
    return null;
  }
  const label = version.version || version.name;
  const outgoing = workflow.versions.find(candidate => candidate.tags.includes(ACTIVE_TAG));
  return (
    <EventAction
      path={version.path}
      reload={reload}
      icon={<PublishIcon fontSize="small" />}
      label="Activate"
      event="activate"
      title={`Activate version ${label}?`}
      explanation={`New instances of ${workflow.title} will be created from version ${label}.`
        + (outgoing
          ? ` Version ${outgoing.version || outgoing.name} is retired in the same step: the instances already`
            + " running against it carry on, but no new ones start from it."
          : " Nothing is retired: this workflow has no active version at the moment.")}
      done={`Version ${label} is now the active version of ${workflow.title}`}
    />
  );
}

export default WorkflowVersionActivateAction;
