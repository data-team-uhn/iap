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

import UndoIcon from "@mui/icons-material/Undo";
import { Button, DialogContentText } from "@mui/material";

import ConfirmActionDialog from "@iap/frontend-commons/components/ConfirmActionDialog";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { moveVersion } from "./workflowWrites";

import type { WorkflowVersionActionProps } from "./WorkflowVersionActions";

// Takes a version off trial and back to being a draft, which is how a trial that needs more work is
// changed: the same node becomes editable again, rather than a copy of it being drafted beside it.
//
// Offered for a trial only. An active version has instances following it and a retired one has
// instances that outlived it, so neither goes back this way — carrying either forward means drafting
// a copy, which is the action next to this one.
function WorkflowVersionRedraftAction({ version, workflow, reload, report }: WorkflowVersionActionProps) {
  const [ confirming, setConfirming ] = useState(false);
  const fetchUtil = useAuthenticatedFetch();

  if (version.state !== "TRIAL") {
    return null;
  }

  const label = version.version || version.name;

  const redraft = (): Promise<void> =>
    moveVersion(fetchUtil, version.path, "returnToDraft").then(() => {
      report(`Version ${label} of ${workflow.title} is a draft again`);
      reload();
    });

  return (
    <>
      <Button size="small" startIcon={<UndoIcon />} onClick={() => setConfirming(true)}>
        Return to draft
      </Button>
      { confirming && (
        <ConfirmActionDialog
          title={`Return version ${label} to draft?`}
          confirmLabel="Return to draft"
          onConfirm={redraft}
          onClose={() => setConfirming(false)}
        >
          <DialogContentText>
            The trial of version {label} ends and its diagram becomes editable again. Nothing else about
            {" "}{workflow.title} changes: whichever version was active stays active.
          </DialogContentText>
        </ConfirmActionDialog>
      )}
    </>
  );
}

export default WorkflowVersionRedraftAction;
