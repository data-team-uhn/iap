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

import type { SerializedNode } from "@iap/frontend-commons/serializedNode";
import { useNode } from "@iap/frontend-commons/useNode";

import { type SubmissionTask, getOpeningRequirement, tasksFrom } from "./taskModel";

// The selectors for reading the workflow container, with the events the reader may send each task. Not
// `simple`, which drops the `sling:resourceType` that tasks are found by. `-dereference` keeps each
// instance's `workflowVersion` as its `jcr:uuid` rather than the whole workflow version.
const CONTAINER_SELECTORS = "deep.-dereference.events";

// What the workflows of a submission wait for: their open tasks, and the requirement their first task
// asked for, if it named one
export interface SubmissionSteps {
  tasks: SubmissionTask[];
  opening?: string;
}

function readSteps(container: SerializedNode): SubmissionSteps {
  return { tasks: tasksFrom(container), opening: getOpeningRequirement(container) };
}

// The open tasks of a submission's workflows, and a way to read them again. A failed read gives no
// tasks, a failed re-read included, since what was read before may include a task already done.
export function useSubmissionTasks(path: string): SubmissionSteps & { reload: () => Promise<void> } {
  const { value, loadError, reload } = useNode(`${path}/wf:instances`, CONTAINER_SELECTORS, readSteps);
  return loadError === undefined && value !== undefined ? { ...value, reload } : { tasks: [], reload };
}
