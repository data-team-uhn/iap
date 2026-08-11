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

// The form a submitter fills in, as the server projects it, and the one way to change it.
//
// Both halves of this are deliberately thin. What to show is decided server-side — conditions are
// resolved there, so a question absent from this document is a question that does not currently
// apply — and what a change means is decided by a workflow. Nothing here evaluates a condition or
// writes to the repository.

// The resource types the projection reports. It names the schema's own types rather than a
// vocabulary of its own, so a requirement kind added later arrives here without a release.
export const FORM_REQUIREMENT = "sch/FormRequirement";
export const SECTION = "sch/Section";
export const QUESTION = "sch/Question";

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

export interface FormRequirement {
  name: string;
  type: string;
  label: string;
  description?: string;
  // Present only for requirements that hold questions; a document or an approval has none
  items?: FormItem[];
}

export interface SubmissionForm {
  path: string;
  title: string;
  // Whether this reader may still answer: the same two rules the save workflow enforces, so the
  // editor offers editing only where a save would be accepted rather than learning from a refusal
  editable: boolean;
  requirements: FormRequirement[];
}

export function isQuestion(item: FormItem): item is FormQuestion {
  return item.type === QUESTION;
}

// Reads the form for a submission: what its schema asks, what it already answers, and nothing that
// does not currently apply.
export async function fetchForm(path: string): Promise<SubmissionForm> {
  const response = await fetch(`${path}.form.json`);
  if (!response.ok) {
    throw new Error(`This request could not be loaded (${response.status})`);
  }
  return (await response.json()) as SubmissionForm;
}

// Records one answer, by posting it to the submission itself. That POST is a `save` event, matched
// by a system workflow — filling a request in is a workflow event and not a write — so a refusal
// arrives as the engine's own reason rather than as a repository error.
export async function saveAnswer(path: string, question: string, values: string[]): Promise<void> {
  const body = new URLSearchParams();
  // A question that may hold several values is answered by repeating it, which is what the handler
  // reads back as a multi-valued answer
  values.forEach(value => body.append(question, value));
  const response = await fetch(path, { method: "POST", body });
  if (!response.ok) {
    const refusal = (await response.json().catch(() => ({}))) as { error?: string };
    throw new Error(refusal.error ?? `This answer could not be saved (${response.status})`);
  }
}
