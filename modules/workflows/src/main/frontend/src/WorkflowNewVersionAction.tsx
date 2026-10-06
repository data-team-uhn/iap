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

import LibraryAddOutlinedIcon from "@mui/icons-material/LibraryAddOutlined";
import { useNavigate } from "react-router";

import { ActionIcon } from "@iap/frontend-commons/components/EventAction";

import NewVersionDialog from "./NewVersionDialog";
import { adminUrl, offers } from "./workflowModel";

import type { WorkflowActionProps } from "./WorkflowActions";

// Opens a new version of a workflow from the shipped starting diagram, and then its editor: a version
// that was just opened exists to be drawn.
function WorkflowNewVersionAction({ workflow }: WorkflowActionProps) {
  const [ creating, setCreating ] = useState(false);
  const navigate = useNavigate();
  if (!offers(workflow, "createVersion")) {
    return null;
  }
  return (
    <>
      <ActionIcon label="New version" icon={<LibraryAddOutlinedIcon fontSize="small" />}
        onClick={() => setCreating(true)} />
      { creating && (
        <NewVersionDialog
          workflow={workflow}
          onClose={() => setCreating(false)}
          onCreated={versionPath => {
            setCreating(false);
            void navigate(adminUrl(versionPath, "edit"));
          }}
        />
      ) }
    </>
  );
}

export default WorkflowNewVersionAction;
