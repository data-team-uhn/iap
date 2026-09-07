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

import PublishIcon from "@mui/icons-material/Publish";
import { Button, DialogContentText } from "@mui/material";

import ConfirmActionDialog from "@iap/frontend-commons/components/ConfirmActionDialog";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { moveVersion } from "./workflowWrites";

import type { WorkflowVersionActionProps } from "./WorkflowVersionActions";

// Promotes a draft, or a version that has been on trial, to the version new instances are created
// from.
//
// Only those two are offered this: what is already active needs no promoting, and bringing a retired
// version back is a decision to make deliberately, by drafting a copy of it.
//
// Confirmed, because the effect lands outside this page — on everything that starts a workflow from
// now on — and because it retires the version that was current, which is not visible from the row
// the button sits in. The retirement is named in the confirmation for exactly that reason.
function WorkflowVersionActivateAction({ version, workflow, reload, report }: WorkflowVersionActionProps) {
  const [ confirming, setConfirming ] = useState(false);
  const fetchUtil = useAuthenticatedFetch();

  if (version.state !== "DRAFT" && version.state !== "TRIAL") {
    return null;
  }

  const label = version.version || version.name;
  const outgoing = workflow.versions.find(candidate => candidate.state === "ACTIVE");

  const activate = (): Promise<void> =>
    moveVersion(fetchUtil, version.path, "activate").then(() => {
      report(`Version ${label} is now the active version of ${workflow.title}`);
      reload();
    });

  return (
    <>
      <Button size="small" startIcon={<PublishIcon />} onClick={() => setConfirming(true)}>
        Activate
      </Button>
      { confirming && (
        <ConfirmActionDialog
          title={`Activate version ${label}?`}
          confirmLabel="Activate"
          onConfirm={activate}
          onClose={() => setConfirming(false)}
        >
          <DialogContentText>
            New instances of {workflow.title} will be created from version {label}.
            { outgoing
              ? ` Version ${outgoing.version || outgoing.name} is retired in the same step: the instances already
                  running against it carry on, but no new ones start from it.`
              : " Nothing is retired: this workflow has no active version at the moment." }
          </DialogContentText>
        </ConfirmActionDialog>
      )}
    </>
  );
}

export default WorkflowVersionActivateAction;
