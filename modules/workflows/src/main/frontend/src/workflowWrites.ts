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

// Every write here posts a domain event at the thing it concerns. The workflow engine runs the
// matching system workflow under /SystemWorkflows to its end event, in one commit.
//
// Nobody holds repository rights on workflow content, so what a user may do here is exactly what
// those definitions say. A multi-step change — a promotion that retires the version it supersedes, a
// draft that arrives with its diagram or as a copy of another version — happens as one atomic run rather
// than two requests that could half-complete.
//
// A selector names the event, e.g. `.create.json`, `.activate.json`.

import type { AuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { sendEvent } from "@iap/frontend-commons/workflowEvents";

import { STARTING_BPMN, bpmnUpload } from "./workflowModel";

// Where a create put what it made, which is where the caller goes next.
async function created(sent: Promise<string | undefined>): Promise<string> {
  const path = await sent;
  if (!path) {
    throw new Error("It was created, but the server did not say where");
  }
  return path;
}

export interface NewWorkflow {
  // The homepage to create it in, e.g. "/Workflows"
  homepage: string;
  title: string;
  version: string;
  description: string;
}

// One event creates the workflow and its first draft version together, starting from the shipped
// diagram, so a workflow with no version is never left behind by a request that failed halfway. The
// server answers with the workflow, which is what was asked for, its version being made on its behalf.
//
// Nothing marks the workflow as runnable directly. That's read off its versions, and the one this
// creates starts as a draft, so the workflow runs nothing until a version is activated.
//
// @return the path of the created workflow
export async function createWorkflow(fetchUtil: AuthenticatedFetch, fields: NewWorkflow): Promise<string> {
  const requested = bpmnUpload(STARTING_BPMN);
  requested.set("title", fields.title);
  requested.set("version", fields.version);
  if (fields.description !== "") {
    requested.set("description", fields.description);
  }
  return created(sendEvent(fetchUtil, fields.homepage, "create", requested));
}

export interface NewVersion {
  version: string;
  description: string;
}

// Creates a draft version from the shipped starting diagram — the "start from scratch" case, as
// opposed to drafting a copy of an existing version.
//
// The diagram travels with the request instead of being posted afterward, so a version with no
// diagram is never an observable state. Posting it separately wouldn't work anyway: Sling creates the
// node a file part's path implies before applying jcr:primaryType, leaving a stray sling:Folder
// behind.
//
// @return the path of the created draft version
export async function createVersion(fetchUtil: AuthenticatedFetch, definitionPath: string, fields: NewVersion):
Promise<string> {
  const requested = bpmnUpload(STARTING_BPMN);
  requested.set("version", fields.version);
  if (fields.description !== "") {
    requested.set("description", fields.description);
  }
  return created(sendEvent(fetchUtil, definitionPath, "createVersion", requested));
}

// The editable properties of a workflow itself, as opposed to those of its versions.
// Whether it runs isn't among them: that's read off the versions, and changed by activating one.
export interface WorkflowFields {
  title: string;
}

// Which properties a save is allowed to touch is the definition's `editable` list, rather than
// whatever this request happens to name.
export async function updateWorkflow(fetchUtil: AuthenticatedFetch, path: string, fields: WorkflowFields):
Promise<void> {
  await sendEvent(fetchUtil, path, "save", { title: fields.title });
}

// Replaces a version's diagram outright. The server refuses this for anything but a draft — not just
// the editor declining to open one.
export async function saveDiagram(fetchUtil: AuthenticatedFetch, versionPath: string, xml: string): Promise<void> {
  await sendEvent(fetchUtil, versionPath, "save", bpmnUpload(xml));
}

// Opens a new draft as a copy of an existing version: a new version of the workflow it belongs to,
// naming it as the source, which the server copies whole, diagram and graph and all, under the new label.
//
// @return the path of the created draft version
export async function draftFromVersion(fetchUtil: AuthenticatedFetch, versionPath: string, version: string):
Promise<string> {
  const workflowPath = versionPath.slice(0, versionPath.lastIndexOf("/"));
  return created(sendEvent(fetchUtil, workflowPath, "createVersion", { version, source: versionPath }));
}
