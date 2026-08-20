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

// What a submission's workflows are currently waiting for, and the one way to answer it.
//
// A running workflow keeps its instances inside the thing it drives, so what a submission is
// waiting on is read from the submission itself rather than looked up in a register somewhere.

import { type JsonNode, childrenOfType } from "./jsonNode";

export const WORKFLOW_INSTANCE = "wf/WorkflowInstance";
export const TASK_INSTANCE = "wf/TaskInstance";

// The same thing as a JCR node type, which is what a query filters on: resource types are
// slash-separated and node types colon-separated, and only the second can appear in a query.
export const TASK_INSTANCE_NODE_TYPE = "wf:TaskInstance";

// The status a task carries until somebody completes it
const OPEN = "created";

// One thing a person still has to do on a submission.
export interface SubmissionTask {
  path: string;
  label: string;
  // The decisions this task may be completed with. Empty means there is nothing to decide: the
  // task is done or it is not, which is what a "send this" step looks like.
  outcomeOptions: string[];
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
  };
}

// Reads the tasks a submission's workflows are waiting on, oldest instance first.
//
// Only the container is fetched rather than the whole submission: the answers are large, this is
// small, and asking for it separately is what lets the editor show the same controls as the
// read-only page without either of them fetching for the other.
export async function fetchOpenTasks(path: string): Promise<SubmissionTask[]> {
  const response = await fetch(`${path}/wf:instances.deep.json`);
  if (!response.ok) {
    // A submission nothing is running on has no container at all, which is a 404 and not a
    // failure: it means there is nothing to do, which is exactly what an empty list says
    if (response.status === 404) {
      return [];
    }
    throw new Error(`What this request is waiting for could not be read (${response.status})`);
  }
  const container = (await response.json()) as JsonNode;
  return childrenOfType(container, WORKFLOW_INSTANCE)
    .flatMap(instance => childrenOfType(instance, TASK_INSTANCE))
    .map(asTask)
    .filter((task): task is SubmissionTask => task !== null);
}

// Completes one task, by posting the decision to the task itself. That POST is a `complete` event
// for the engine, which is what carries the workflow on from there — so a refusal arrives as the
// engine's own reason, and everything that follows from the decision lands in the same commit.
export async function completeTask(
  post: (url: string, init?: RequestInit) => Promise<Response>,
  task: SubmissionTask,
  outcome?: string,
  note?: string,
): Promise<void> {
  const body = new URLSearchParams();
  if (outcome) {
    body.append("outcome", outcome);
  }
  // Sent only when there is something to send: the engine treats a blank note as nothing said, and
  // posting one anyway would have every decision carry an empty explanation
  if (note?.trim()) {
    body.append("outcomeNote", note);
  }
  const response = await post(task.path, { method: "POST", body });
  if (!response.ok) {
    const refusal = (await response.json().catch(() => ({}))) as { error?: string };
    throw new Error(refusal.error ?? `This could not be done (${response.status})`);
  }
}
