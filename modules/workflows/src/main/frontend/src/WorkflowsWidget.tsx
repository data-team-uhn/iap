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

import { useEffect, useState } from "react";

import { CircularProgress, List, ListItem, ListItemText, Typography } from "@mui/material";

import { WORKFLOWS_ROOT, parseWorkflowList, type WorkflowVersionSummary } from "./workflowModel";

// The administration console widget summarizing the workflows: one line per workflow version,
// read-only. It reuses the same listing plumbing as the BPMN editor, which itself is behind the
// widget frame's "Manage workflows" action (see the extension node).
function WorkflowsWidget() {
  const [ versions, setVersions ] = useState<WorkflowVersionSummary[]>();
  const [ loadError, setLoadError ] = useState(false);

  useEffect(() => {
    fetch(`${WORKFLOWS_ROOT}.2.json`)
      .then(response => {
        if (!response.ok) {
          throw new Error(response.statusText);
        }
        return response.json();
      })
      .then((data: Record<string, unknown>) => setVersions(parseWorkflowList(data)))
      .catch(() => setLoadError(true));
  }, []);

  if (loadError) {
    return <Typography color="error" variant="body2">The workflows could not be loaded.</Typography>;
  }
  if (!versions) {
    return <CircularProgress size={24} sx={{ display: "block", mx: "auto", my: 2 }} />;
  }
  if (versions.length === 0) {
    return <Typography color="textSecondary" variant="body2">No workflows are defined yet.</Typography>;
  }

  return (
    <List dense disablePadding>
      { versions.map(version => (
        <ListItem key={version.path} disableGutters sx={{ py: 0 }}>
          <ListItemText
            primary={`${version.title} (v${version.version})`}
            secondary={version.description || null}
          />
        </ListItem>
      )) }
    </List>
  );
}

export default WorkflowsWidget;
