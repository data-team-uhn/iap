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

// The status of a task nobody has completed or cancelled yet
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
  // The requirement the task asks the submitter to fill in, when it names one. The editor shows that
  // requirement as the task's step.
  requirement?: string;
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
    ...(typeof node.requirement === "string" ? { requirement: node.requirement } : {}),
  };
}

// When a task was created. A task with no readable time sorts last.
function getCreatedTime(task: SerializedNode): number {
  const created = typeof task["jcr:created"] === "string" ? Date.parse(task["jcr:created"]) : NaN;
  return Number.isNaN(created) ? Infinity : created;
}

// The task a workflow created first. The server lists a workflow's tasks in no set order.
function getFirstTask(instance: SerializedNode): SerializedNode | undefined {
  return childrenOfType(instance, TASK_INSTANCE_TYPE).reduce<SerializedNode | undefined>(
    (first, task) => first === undefined || getCreatedTime(task) < getCreatedTime(first) ? task : first,
    undefined);
}

// The requirement the first task of a workflow asked for, done or not. A workflow that opens by asking
// for a requirement takes its way from the answer, so the editor keeps that requirement on show.
export function getOpeningRequirement(container: SerializedNode): string | undefined {
  return childrenOfType(container, WORKFLOW_INSTANCE_TYPE)
    .map(instance => getFirstTask(instance)?.requirement)
    .find((requirement): requirement is string => typeof requirement === "string");
}

// The open tasks in a submission's serialized workflow container.
export function tasksFrom(container: SerializedNode): SubmissionTask[] {
  return childrenOfType(container, WORKFLOW_INSTANCE_TYPE)
    .flatMap(instance => childrenOfType(instance, TASK_INSTANCE_TYPE))
    .map(asTask)
    .filter((task): task is SubmissionTask => task !== null);
}
