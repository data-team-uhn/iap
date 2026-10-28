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

// The open tasks in a submission's workflows, read from its serialized `wf:instances` container. No React, no fetch.

import { type SerializedNode, childrenOfType, lastSegmentOf, stringsOf } from "@iap/frontend-commons/serializedNode";

export const WORKFLOW_INSTANCE_TYPE = "wf/WorkflowInstance";
export const TASK_INSTANCE_TYPE = "wf/TaskInstance";

// The event that completes a task
export const COMPLETE_EVENT = "complete";

// The status a task carries until somebody completes it
export const OPEN_STATUS = "created";

// One thing a person still has to do on a submission.
export interface SubmissionTask {
  path: string;
  label: string;
  // The decisions this task may be completed with. Empty for a step with nothing to decide, such as
  // sending the request.
  outcomeOptions: string[];
  // The events the reader may send the task, as the server listed them: `complete` when the task
  // is theirs to do
  "@events": string[];
}

function asTask(node: SerializedNode): SubmissionTask | null {
  const path = node["@path"];
  if (typeof path !== "string" || node.status !== OPEN_STATUS) {
    return null;
  }
  return {
    path,
    label: typeof node.label === "string" ? node.label : lastSegmentOf(path),
    outcomeOptions: stringsOf(node.outcomeOptions),
    "@events": stringsOf(node["@events"]),
  };
}

// The open tasks in a submission's serialized workflow container.
export function tasksFrom(container: SerializedNode): SubmissionTask[] {
  return childrenOfType(container, WORKFLOW_INSTANCE_TYPE)
    .flatMap(instance => childrenOfType(instance, TASK_INSTANCE_TYPE))
    .map(asTask)
    .filter((task): task is SubmissionTask => task !== null);
}
