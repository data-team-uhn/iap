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

import { Button, Stack, Typography } from "@mui/material";

// A small dashboard widget linking to the full workflow editor
function WorkflowEditorWidget() {
  return (
    <Stack spacing={1} sx={{ alignItems: "flex-start" }}>
      <Typography variant="body2" color="text.secondary">
        Create and edit workflow definitions using the visual BPMN editor.
      </Typography>
      <Button href="/Workflows.html">
        Open Workflow Editor
      </Button>
    </Stack>
  );
}

export default WorkflowEditorWidget;
