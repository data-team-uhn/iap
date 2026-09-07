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
// draft that arrives with its diagram — happens as one atomic run rather than two requests that could
// half-complete.
//
// A POST with no selector fires the target's default event (`create` at a homepage, `save` at an
// entity). A selector names any other event outright, e.g. `.activate.json`, `.draft.json`.

import type { AuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { RequestError } from "@iap/frontend-commons/requestFailure";

import { STARTING_BPMN, bpmnUpload } from "./workflowModel";

// How the engine explains a refusal: no workflow was waiting for this event, this user is not among
// the performers of the one that was, the payload was unusable, or the target has moved past the
// state the event was for.
interface EngineRefusal {
  error?: string;
}

// A refusal from the workflow engine, carrying the reason it gave.
// Kept separate from RequestError so that reason reaches the caller directly.
export class OperationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "OperationError";
  }
}

// Sends an event and reads the engine's answer, preferring its explanation to the status code.
async function send(fetchUtil: AuthenticatedFetch, url: string, body: URLSearchParams | FormData):
Promise<Response> {
  const response = await fetchUtil(url, { method: "POST", body });
  if (!response.ok) {
    // The engine's own report of what it would not do; a response that is not the JSON we expect
    // leaves the status code to speak instead
    const answer = await response.json().catch(() => ({})) as EngineRefusal;
    throw answer.error ? new OperationError(answer.error) : new RequestError(response.status);
  }
  return response;
}

// The engine answers a create with a redirect to the new entity, so the followed request's final URL
// is where it lives. The same thing the submissions screens read, and the only answer that survives
// fetch following the redirect on its own.
function createdPath(response: Response): string {
  if (!response.redirected) {
    throw new OperationError("It was created, but the server did not say where");
  }
  return new URL(response.url).pathname;
}

export interface NewWorkflow {
  // The homepage to create it in, e.g. "/Workflows"
  homepage: string;
  title: string;
  version: string;
  description: string;
}

// Fires two events, not one: a deployment may want createWorkflow and createVersion to behave
// differently, so each can grow its own validation step or notification independently.
//
// Nothing marks the workflow as runnable directly. That's read off its versions, and the one this
// creates starts as a draft, so the workflow runs nothing until a version is activated.
//
// @return the path of the created draft version
export async function createWorkflow(fetchUtil: AuthenticatedFetch, fields: NewWorkflow): Promise<string> {
  const requested = new URLSearchParams();
  requested.set("title", fields.title);
  const definitionPath = createdPath(await send(fetchUtil, fields.homepage, requested));

  return createVersion(fetchUtil, definitionPath, {
    version: fields.version,
    description: fields.description,
  });
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
  return createdPath(await send(fetchUtil, `${definitionPath}.createVersion.json`, requested));
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
  const body = new URLSearchParams();
  body.set("title", fields.title);
  await send(fetchUtil, path, body);
}

// Replaces a version's diagram outright. The server refuses this for anything but a draft — not just
// the editor declining to open one.
export async function saveDiagram(fetchUtil: AuthenticatedFetch, versionPath: string, xml: string): Promise<void> {
  await send(fetchUtil, versionPath, bpmnUpload(xml));
}

// Each move is its own system workflow, so which versions it applies to and who may perform it is
// that definition's business — different moves can require different people, e.g. an author
// redrafting their own trial but only an administrator activating one.
export type VersionTransition = "activate" | "startTrial" | "returnToDraft";

// Activation retires whichever version was current in the same commit, so the workflow is never
// between the two.
export async function moveVersion(fetchUtil: AuthenticatedFetch, versionPath: string,
  transition: VersionTransition): Promise<void> {
  await send(fetchUtil, `${versionPath}.${transition}.json`, new URLSearchParams());
}

// Opens a new draft from an existing version, copying its diagram.
//
// @return the path of the created draft version
export async function draftFromVersion(fetchUtil: AuthenticatedFetch, versionPath: string, version: string):
Promise<string> {
  const body = new URLSearchParams();
  body.set("version", version);
  return createdPath(await send(fetchUtil, `${versionPath}.draft.json`, body));
}
