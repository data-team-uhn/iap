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

import EditIcon from "@mui/icons-material/Edit";
import { Button } from "@mui/material";
import { Link as RouterLink } from "react-router";

import { adminUrl } from "./workflowModel";

import type { WorkflowVersionActionProps } from "./WorkflowVersionActions";

// Opens a draft's diagram in the editor, for editing.
//
// Only a draft: an active version is what running instances are following, and a retired one is what
// the instances that outlived it are still following, so editing either would change a process out
// from under the things executing it. Carrying one forward means drafting a copy, which is the
// action next to this one.
function WorkflowVersionEditAction({ version }: WorkflowVersionActionProps) {
  if (version.state !== "DRAFT") {
    return null;
  }
  return (
    <Button
      size="small"
      startIcon={<EditIcon />}
      component={RouterLink}
      to={adminUrl(version.path, "edit")}
    >
      Edit
    </Button>
  );
}

export default WorkflowVersionEditAction;
