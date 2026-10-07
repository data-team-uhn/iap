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

// What a submission's workflows are currently waiting for. No React, no fetch.
//
// A running workflow keeps its instances inside the thing it drives, so what a submission is
// waiting on is read from the submission itself rather than looked up in a register somewhere.

import { type JsonNode, childrenOfType } from "./jsonNode";

export const WORKFLOW_INSTANCE = "wf/WorkflowInstance";
export const TASK_INSTANCE = "wf/TaskInstance";

// The event that completes a task
export const COMPLETE = "complete";

// The status a task carries until somebody completes it
const OPEN = "created";

// One thing a person still has to do on a submission.
export interface SubmissionTask {
  path: string;
  label: string;
  // The decisions this task may be completed with. Empty means there is nothing to decide: the
  // task is done or it is not, which is what a "send this" step looks like.
  outcomeOptions: string[];
  // The events the reader may send the task, as the server listed them: `complete` when the task
  // is theirs to do
  "@events": string[];
}

function strings(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.filter((entry): entry is string => typeof entry === "string");
  }
  // A single-valued property is serialized as a bare string, not as a one-element array
  return typeof value === "string" ? [ value ] : [];
}

function asTask(node: JsonNode): SubmissionTask | null {
  const path = node["@path"];
  if (typeof path !== "string" || node.status !== OPEN) {
    return null;
  }
  return {
    path,
    label: typeof node.label === "string" ? node.label : path.substring(path.lastIndexOf("/") + 1),
    outcomeOptions: strings(node.outcomeOptions),
    "@events": strings(node["@events"]),
  };
}

// The open tasks in a submission's serialized workflow container, oldest instance first.
export function tasksFrom(container: JsonNode): SubmissionTask[] {
  return childrenOfType(container, WORKFLOW_INSTANCE)
    .flatMap(instance => childrenOfType(instance, TASK_INSTANCE))
    .map(asTask)
    .filter((task): task is SubmissionTask => task !== null);
}
