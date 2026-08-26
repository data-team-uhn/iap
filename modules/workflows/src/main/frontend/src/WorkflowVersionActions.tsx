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

import { Fragment, useEffect, useState } from "react";

import { Stack } from "@mui/material";

import { getActions, type ActionComponent } from "@iap/frontend-commons/actionsManager";

import type { WorkflowSummary, WorkflowVersionSummary } from "./workflowModel";

// The extension point contributing the actions. A module adding an action to this bar ships an
// `ext:Extension` on this point and nothing else; the manager page never learns of it.
export const VERSION_ACTIONS_POINT = "WorkflowVersionActions";

// What every action receives. The version and the workflow it belongs to, and the two things an
// action may need to do afterwards: have the page read the workflow again, and say what happened —
// an action that navigates away, or that changed nothing, uses neither.
export interface WorkflowVersionActionProps {
  version: WorkflowVersionSummary;
  workflow: WorkflowSummary;
  reload: () => void;
  report: (message: string) => void;
}

// The actions available on one workflow version, in the order the repository lists them.
//
// Each action decides for itself whether it applies to the version it is handed — editing is for
// drafts, drafting a copy is for what has already been active — so the bar is the same component on
// every row, and a version with nothing to offer renders an empty one.
function WorkflowVersionActions(props: WorkflowVersionActionProps) {
  const [ actions, setActions ] = useState<ActionComponent[]>([]);

  useEffect(() => {
    let cancelled = false;
    void getActions(VERSION_ACTIONS_POINT).then(loaded => {
      if (!cancelled) {
        setActions(loaded);
      }
    });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: "center", flexWrap: "wrap" }}>
      { actions.map((Action, index) => (
        // Actions come from the repository in an order, and are keyed by it: there is nothing else
        // stable to key them by, and the list only changes when the page is loaded again
        <Fragment key={`action-${index}`}>
          <Action {...props} />
        </Fragment>
      )) }
    </Stack>
  );
}

export default WorkflowVersionActions;
