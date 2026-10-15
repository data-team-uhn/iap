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

// The decisions a submission's workflows are waiting for, and the way to make one, for oneself or on
// behalf of someone else. A trimmed port of IAP-88's task list. Which decisions are the reader's is
// the server's answer, and so is whether they may make one for someone else; the engine still refuses
// whoever may not.

import type { AuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { sendEvent } from "@iap/frontend-commons/workflowEvents";

type JsonNode = Record<string, unknown>;

const WORKFLOW_INSTANCE = "wf/WorkflowInstance";
const TASK_INSTANCE = "wf/TaskInstance";

// The same thing as a JCR node type, which is what a query filters on
export const TASK_INSTANCE_NODE_TYPE = "wf:TaskInstance";

// The status a task carries until somebody completes it
const OPEN = "created";

// One decision a submission is waiting for.
export interface SubmissionTask {
  path: string;
  label: string;
  outcomes: string[];
  // Whether the reader is among those it waits for; otherwise they may only make it for someone who is
  mine: boolean;
}

function isNode(value: unknown): value is JsonNode {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function childrenOfType(node: JsonNode, resourceType: string): JsonNode[] {
  return Object.values(node)
    .filter(isNode)
    .filter(child => child["sling:resourceType"] === resourceType);
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
  const outcomes = strings(node.offeredOutcomes);
  const mine = node["@mine"] === true;
  // Neither theirs nor theirs to delegate is dropped rather than shown disabled: what an approver may do
  // with a request is not the requester's business
  if (typeof path !== "string" || node.status !== OPEN || outcomes.length === 0
    || !(mine || node["@delegable"] === true)) {
    return null;
  }
  return {
    path,
    label: typeof node.label === "string" ? node.label : path.substring(path.lastIndexOf("/") + 1),
    outcomes,
    mine,
  };
}

// Reads the open decisions on a submission, oldest instance first.
export async function fetchOpenTasks(doFetch: AuthenticatedFetch, path: string): Promise<SubmissionTask[]> {
  const response = await doFetch(`${path}/wf:instances.deep.json`);
  // A submission nothing is running on has no container at all: nothing to decide, not a failure
  if (response.status === 404) {
    return [];
  }
  if (!response.ok) {
    throw new Error(`What this request is waiting for could not be read (${response.status})`);
  }
  const container = (await response.json()) as JsonNode;
  return childrenOfType(container, WORKFLOW_INSTANCE)
    .flatMap(instance => childrenOfType(instance, TASK_INSTANCE))
    .map(asTask)
    .filter((task): task is SubmissionTask => task !== null);
}

// Completes a task with a decision. Naming someone in `onBehalfOf` records it as theirs; only
// delegates may, and the engine refuses anyone else with the same answer as any other refusal.
export async function completeTask(
  doFetch: AuthenticatedFetch,
  task: SubmissionTask,
  outcome: string,
  onBehalfOf?: string,
): Promise<void> {
  const params: Record<string, string> = { outcome };
  if (onBehalfOf?.trim()) {
    params[":onBehalfOf"] = onBehalfOf.trim();
  }
  await sendEvent(doFetch, task.path, "complete", params);
}
