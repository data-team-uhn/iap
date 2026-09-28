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

import UnpublishedIcon from "@mui/icons-material/Unpublished";
import { Button, DialogContentText } from "@mui/material";

import ConfirmActionDialog from "@iap/frontend-commons/components/ConfirmActionDialog";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { moveVersion } from "./workflowWrites";

import type { WorkflowVersionActionProps } from "./WorkflowVersionActions";

// Withdraws the active version without promoting another in its place, which leaves the workflow
// retired until one of its versions is activated again. Offered for the active version only: nothing
// is following a draft or a trial, and a retired version is already withdrawn.
//
// Confirmed, because the effect lands outside this page: nothing can start this workflow from now on.
function WorkflowVersionRetireAction({ version, workflow, reload, report }: WorkflowVersionActionProps) {
  const [ confirming, setConfirming ] = useState(false);
  const fetchUtil = useAuthenticatedFetch();

  if (version.state !== "ACTIVE") {
    return null;
  }

  const label = version.version || version.name;

  const retire = (): Promise<void> =>
    moveVersion(fetchUtil, version.path, "retire").then(() => {
      report(`Version ${label} is retired, and ${workflow.title} has no active version`);
      reload();
    });

  return (
    <>
      <Button size="small" startIcon={<UnpublishedIcon />} onClick={() => setConfirming(true)}>
        Retire
      </Button>
      { confirming && (
        <ConfirmActionDialog
          title={`Retire version ${label}?`}
          confirmLabel="Retire"
          onConfirm={retire}
          onClose={() => setConfirming(false)}
        >
          <DialogContentText>
            No new instances of {workflow.title} can be started until a version is activated again. The
            instances already running against version {label} carry on.
          </DialogContentText>
        </ConfirmActionDialog>
      )}
    </>
  );
}

export default WorkflowVersionRetireAction;
