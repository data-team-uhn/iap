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

import { type AuthenticatedFetch } from "@iap/frontend-commons/reLogin";

// The form a submitter fills in, as the server projects it, and the one way to change it.
//
// Neither half of this decides anything. What to show is decided server-side, where the
// conditions are resolved, so a question absent from this document is one that does not currently
// apply. What a change means is decided by a workflow. Nothing here evaluates a condition or writes
// to the repository.

// The resource types the projection reports. It names the schema's own types rather than a
// vocabulary of its own, so a requirement kind added later arrives here without a release.
export const FORM_REQUIREMENT = "sch/FormRequirement";
export const DOCUMENT_REQUIREMENT = "sch/DocumentRequirement";
export const SECTION = "sch/Section";
export const QUESTION = "sch/Question";

// One of the answers a question offers. The value is what an answer stores and what a condition
// compares against; the label is only what the submitter reads.
export interface FormAnswerOption {
  value: string;
  label: string;
}

export interface FormQuestion {
  name: string;
  type: string;
  // Where this question's answer is posted, relative to the schema version. Given by the server so
  // that only one side of the exchange decides how a question is addressed.
  path: string;
  text: string;
  description?: string;
  // One of text, long, double, boolean, date, file
  dataType: string;
  required: boolean;
  multiple: boolean;
  // The answers this question offers. Absent, or empty, when it is answered freely, which is how
  // most question types are answered.
  options?: FormAnswerOption[];
  value: string[];
}

export interface FormSection {
  name: string;
  type: string;
  label: string;
  description?: string;
  items: FormItem[];
}

export type FormItem = FormQuestion | FormSection;

// Anything a schema version asks of a submission, whatever form that takes.
export interface Requirement {
  name: string;
  // The repository path, which is what an attached document's `fulfills` points at
  path: string;
  type: string;
  label: string;
  description?: string;
}

// The one kind answered by filling questions in. A document or an approval is satisfied some other
// way and carries no items, which is why they are not this type.
export interface FormRequirement extends Requirement {
  items: FormItem[];
}

// The kind answered by attaching a file.
export interface DocumentRequirement extends Requirement {
  // Whether the submission is incomplete without it. An optional one is still asked, since whether it
  // is asked at all was decided on the server, but skipping it blocks nothing.
  required: boolean;
  // Empty means no restriction, which is why the key is there at all: a reader has to tell "takes
  // anything" from "takes nothing".
  acceptedFileTypes: string[];
  // A document to start from, where the requirement offers one
  template?: string;
  // The name to save the template under, since its node is always called `template`
  templateName?: string;
  // What has been attached already, by title. Present so that reopening the form shows a document
  // that is there rather than an empty control implying it is not.
  attached: string[];
}

export interface SubmissionForm {
  path: string;
  title: string;
  // Whether this reader may still answer, as the server decided it
  editable: boolean;
  requirements: Requirement[];
}

export function isQuestion(item: FormItem): item is FormQuestion {
  return item.type === QUESTION;
}

export function isFormRequirement(requirement: Requirement): requirement is FormRequirement {
  return requirement.type === FORM_REQUIREMENT;
}

export function isDocumentRequirement(requirement: Requirement): requirement is DocumentRequirement {
  return requirement.type === DOCUMENT_REQUIREMENT;
}

// What an empty document slot says. An optional one says so, or it reads as a gap.
export function describeNothingAttached(requirement: DocumentRequirement): string {
  return requirement.required ? "Nothing attached yet" : "Nothing attached yet — optional";
}

// Reads the form for a submission: what its schema asks, what it already answers, and nothing that
// does not currently apply.
export async function fetchForm(doFetch: AuthenticatedFetch, path: string): Promise<SubmissionForm> {
  const response = await doFetch(`${path}.form.json`);
  if (!response.ok) {
    throw new Error(`This request could not be loaded (${response.status})`);
  }
  return (await response.json()) as SubmissionForm;
}

// Records one answer, by posting it to the submission as a `save` event: filling a request in is a
// workflow event and not a write, so a refusal arrives as the engine's own reason rather than as a
// repository error. The selector names the event outright, though a bare POST to a submission means
// `save` too.
export async function saveAnswer(
  doFetch: AuthenticatedFetch, path: string, question: string, values: string[]): Promise<void> {
  const body = new URLSearchParams();
  // A question that may hold several values is answered by repeating it, which is what the handler
  // reads back as a multi-valued answer
  values.forEach(value => body.append(question, value));
  if (values.length === 0) {
    // Clearing an answer still has to name the question, with one empty value: the handler walks the
    // questions the payload mentions, so a question left out of it is not cleared but *untouched*.
    // The widgets report no values at all for a blank field, which without this reads as "nothing to
    // say about this question" -- the old answer survives, and the request goes on counting as
    // complete when it no longer is.
    body.append(question, "");
  }
  const response = await doFetch(`${path}.save.json`, { method: "POST", body });
  if (!response.ok) {
    const refusal = (await response.json().catch(() => ({}))) as { error?: string };
    throw new Error(refusal.error ?? `This answer could not be saved (${response.status})`);
  }
}

// Attaches a file to the requirement it answers, as an `attachDocument` event on the submission:
// uploading is a workflow step for the same reason answering is, so what may be attached and until
// when is the handler's answer rather than a permission on the folder.
//
// `FormData` rather than a query string, and deliberately without a `Content-Type`: the browser has
// to set it, because only it knows the multipart boundary it just generated.
export async function attachDocument(
  doFetch: AuthenticatedFetch, path: string, requirement: string, file: File): Promise<void> {
  const body = new FormData();
  body.append("requirement", requirement);
  body.append("file", file);
  const response = await doFetch(`${path}.attachDocument.json`, { method: "POST", body });
  if (!response.ok) {
    const refusal = (await response.json().catch(() => ({}))) as { error?: string };
    throw new Error(refusal.error ?? `This file could not be attached (${response.status})`);
  }
}
