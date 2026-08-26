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

// Every write the workflow screens make: creating a workflow or a version, editing a workflow's
// properties, saving a diagram, and the lifecycle transitions.
//
// None of them writes to the repository. Each is a domain event posted at the thing it concerns,
// which the workflow engine matches against the system workflow waiting for it under
// /SystemWorkflows and runs to its end event in one commit. That is not a detour: nobody holds
// repository rights on workflow content, so what a user may do here is what those definitions say
// rather than what an ACL allows, and a promotion that retires the version it supersedes, or a
// draft that arrives with its diagram, is one run rather than two requests that could half happen.
//
// Which event each of these sends is the only thing this module decides. A POST with no selector
// means the target's default event -- `create` at a homepage, `save` at an entity -- and a selector
// names any other event outright: `.activate.json`, `.draft.json`.

import type { AuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { RequestError } from "@iap/frontend-commons/requestFailure";

import { STARTING_BPMN, bpmnUpload } from "./workflowModel";

// How the engine explains a refusal: no workflow was waiting for this event, this user is not among
// the performers of the one that was, the payload was unusable, or the target has moved past the
// state the event was for.
interface EngineRefusal {
  error?: string;
}

// A refusal from the workflow engine, carrying the explanation it gave. Separate from RequestError
// because the engine answers a refused event with a reason worth reading, where a bare status code
// would leave the caller inventing one.
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

// Where an event that created something says it put it. The engine answers with a redirect to the
// new entity, so the final URL of the followed request is where it lives -- the same thing the
// submissions screens read, and the only answer that survives fetch following the redirect on its
// own.
function createdPath(response: Response): string {
  if (!response.redirected) {
    throw new OperationError("It was created, but the server did not say where");
  }
  return new URL(response.url).pathname;
}

// The fields a new workflow is created with.
export interface NewWorkflow {
  // The homepage to create it in, e.g. "/Workflows"
  homepage: string;
  title: string;
  version: string;
  description: string;
}

// Creates a workflow and its first version.
//
// Two events rather than one, because they are two things a deployment may want to say something
// different about: what happens when a workflow is asked for, and what happens when a version of
// one is. The first is /SystemWorkflows/createWorkflow, the second /SystemWorkflows/createVersion,
// and either can grow a validation step or a notification without the other.
//
// Nothing marks the workflow as runnable: that is read off its versions, and the only one it has
// starts as a draft, so a new workflow runs nothing until a version of it is activated.
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

// The fields a new version is created with.
export interface NewVersion {
  version: string;
  description: string;
}

// Creates a draft version of an existing workflow, starting from the shipped starting diagram -- the
// "another version, from scratch" case, as against drafting a copy of an existing one.
//
// The diagram travels with the request rather than following it: the handler creates the version and
// stores the diagram under it in one run, so a version with no diagram is never a state anything can
// observe. Posting directly could not do that -- Sling creates the node a file part's path implies
// before it applies jcr:primaryType, so a combined write left a sling:Folder behind.
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

// The editable properties of a workflow itself, as against those of its versions. Whether it runs
// is not among them: that is read off its versions, and is changed by activating one of them.
export interface WorkflowFields {
  title: string;
}

// Saves a workflow's own properties. Which properties a save is allowed to touch is the definition's
// `editable` list rather than whatever this request happens to name.
export async function updateWorkflow(fetchUtil: AuthenticatedFetch, path: string, fields: WorkflowFields):
Promise<void> {
  const body = new URLSearchParams();
  body.set("title", fields.title);
  await send(fetchUtil, path, body);
}

// Saves a version's diagram, replacing whatever it held. Refused for anything but a draft -- by the
// server, now, rather than only by the editor declining to open one.
export async function saveDiagram(fetchUtil: AuthenticatedFetch, versionPath: string, xml: string): Promise<void> {
  await send(fetchUtil, versionPath, bpmnUpload(xml));
}

// The moves a version's lifecycle has, named as the events that make them happen. Each is a system
// workflow of its own, so which versions a move applies to and who may make it are that definition's
// business -- there is no state parameter to send, because asking for a state rather than for a move
// is what made "an author may redraft their own trial, but only an administrator may activate one"
// impossible to say.
export type VersionTransition = "activate" | "startTrial" | "returnToDraft";

// Moves a version to another state of its lifecycle. Activation retires whichever version was
// current in the same commit, so the workflow is never between the two.
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
