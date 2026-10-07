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

import { Skeleton, Typography } from "@mui/material";

import WidgetStatList from "@iap/frontend-commons/components/WidgetStatList";

import { useWorkflowCounts } from "./useWorkflowCounts";
import { adminUrl } from "./workflowModel";

// The administration console widget summarizing the workflows: how many each homepage holds, each
// homepage's name leading to its own listing. The frame's "Manage workflows" action, from the
// extension node, leads to the one every deployment has.
function WorkflowsWidget() {
  const { counts, loading } = useWorkflowCounts();

  if (loading) {
    return <Skeleton variant="rounded" height={96} aria-label="Loading the workflows" />;
  }
  if (counts.length === 0) {
    return <Typography variant="placeholder">No workflows are defined yet.</Typography>;
  }
  return (
    <WidgetStatList
      stats={counts.map(homepage => ({
        label: homepage.title,
        value: homepage.count,
        approximate: homepage.atLeast,
        href: adminUrl(homepage.path),
        unknownTitle: "The workflows here could not be counted",
      }))}
    />
  );
}

export default WorkflowsWidget;
