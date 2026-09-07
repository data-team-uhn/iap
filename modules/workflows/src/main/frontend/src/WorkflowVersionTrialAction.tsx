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

import ScienceIcon from "@mui/icons-material/Science";
import { Button, DialogContentText } from "@mui/material";

import ConfirmActionDialog from "@iap/frontend-commons/components/ConfirmActionDialog";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { moveVersion } from "./workflowWrites";

import type { WorkflowVersionActionProps } from "./WorkflowVersionActions";

// Puts a draft on trial: the diagram is frozen, as it is for anything past drafting, but the version
// is not yet the one new instances are created from.
//
// Offered for a draft only — what is on trial is already there, and an active or retired version is
// carried forward by drafting a copy of it rather than by being tried again.
//
// Confirmed, like every transition here: the row it sits in is not the place to report that the
// server refused, and freezing the diagram is worth saying out loud to whoever was drawing it.
function WorkflowVersionTrialAction({ version, workflow, reload, report }: WorkflowVersionActionProps) {
  const [ confirming, setConfirming ] = useState(false);
  const fetchUtil = useAuthenticatedFetch();

  if (version.state !== "DRAFT") {
    return null;
  }

  const label = version.version || version.name;

  const trial = (): Promise<void> =>
    moveVersion(fetchUtil, version.path, "startTrial").then(() => {
      report(`Version ${label} of ${workflow.title} is on trial`);
      reload();
    });

  return (
    <>
      <Button size="small" startIcon={<ScienceIcon />} onClick={() => setConfirming(true)}>
        Start trial
      </Button>
      { confirming && (
        <ConfirmActionDialog
          title={`Put version ${label} on trial?`}
          confirmLabel="Start trial"
          onConfirm={trial}
          onClose={() => setConfirming(false)}
        >
          <DialogContentText>
            Version {label} stops being editable: a trial is tried as it stands. It does not become the version new
            instances of {workflow.title} are created from — activating it is a separate step, and returning it to
            being a draft is how it is changed again.
          </DialogContentText>
        </ConfirmActionDialog>
      )}
    </>
  );
}

export default WorkflowVersionTrialAction;
