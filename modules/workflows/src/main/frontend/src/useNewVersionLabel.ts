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

import { type ChangeEvent, useState } from "react";

import { nextVersionLabel } from "@iap/frontend-commons/versionNumbers";

import type { WorkflowSummary } from "./workflowModel";

// The label a new version of the workflow is given: the next free one to start with, refused while it repeats
// one the workflow already has. `field` holds the props of the text field it is typed in.
export default function useNewVersionLabel(workflow: WorkflowSummary) {
  const [ version, setVersion ] = useState(() => nextVersionLabel(workflow.versions.map(existing => existing.name)));
  const label = version.trim();
  const duplicate = label !== "" && workflow.versions.some(existing => existing.version === label);
  return {
    label,
    valid: label !== "" && !duplicate,
    field: {
      label: "Version",
      required: true,
      fullWidth: true,
      value: version,
      onChange: (event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => setVersion(event.target.value),
      placeholder: "e.g. 2.0",
      error: duplicate,
      helperText: duplicate ? "This workflow already has a version with that label" : undefined,
    },
  };
}
