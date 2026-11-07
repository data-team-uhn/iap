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
import { readJson } from "@iap/frontend-commons/requestFailure";

// The form a submitter fills in, as the server serves it at `<submission>.form.json`, and the
// events that change the submission. The server resolves the conditions, so a question absent from
// the form does not apply now.

// The resource types the form reports, which are the schema's own. A requirement kind that another
// module adds reports its own type.
export const FORM_REQUIREMENT_TYPE = "sch/FormRequirement";
export const DOCUMENT_REQUIREMENT_TYPE = "sch/DocumentRequirement";
export const APPROVAL_REQUIREMENT_TYPE = "sch/ApprovalRequirement";
export const SECTION = "sch/Section";
export const QUESTION = "sch/Question";

// One of the answers a question offers. The value is what an answer stores and what a condition
// compares against. The label is what the submitter reads.
export interface FormAnswerOption {
  value: string;
  label: string;
}

export interface FormQuestion {
  name: string;
  type: string;
  // The question's path relative to the schema version, such as `details/duration`. A save names the
  // question by it.
  path: string;
  text: string;
  description?: string;
  // The type of value an answer holds, such as text, long, double, boolean or date
  dataType: string;
  // How many values an answer takes, as the schema stores it. A positive minimum makes the question
  // required, and a maximum other than 1 allows several values. Zero or less leaves that end open.
  // Read it through isRequired, isMultiple and answerCap.
  minAnswers: number;
  maxAnswers: number;
  // The bounds of a numeric answer, present only where the schema states them. The save refuses a
  // value outside them.
  minValue?: number;
  maxValue?: number;
  // A regular expression every value must match in full, and what to tell the submitter when one
  // does not. The save refuses a value that does not match, with the message.
  pattern?: string;
  patternMessage?: string;
  // The answers the question offers; empty when it is answered freely
  options: FormAnswerOption[];
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
  // The requirement's repository path. An attached document's `fulfills` points at it.
  path: string;
  type: string;
  label: string;
  description?: string;
}

// A requirement answered by filling questions in. Only this kind holds items.
export interface FormRequirement extends Requirement {
  items: FormItem[];
}

// A requirement answered by attaching a file.
export interface DocumentRequirement extends Requirement {
  // Whether the submission is incomplete without a file for it
  required: boolean;
  // The MIME types a file may have, such as `application/pdf`; empty when any type is accepted
  acceptedFileTypes: string[];
  // The path of a document to start from, when the requirement offers one
  template?: string;
  // The name to save the template under, since its node is always called `template`
  templateName?: string;
  // The documents attached for it; empty when none is
  attached: AttachedDocument[];
}

// A requirement somebody else grants, with where it stands.
export interface ApprovalRequirement extends Requirement {
  // The group whose members decide, empty when it is not narrowed to one
  approverGroup: string;
  // The latest decision, absent until somebody has approved or rejected it
  decision?: ApprovalDecision;
}

// Which way an approval was decided, who decided it, and when, where that is recorded.
export interface ApprovalDecision {
  approved: boolean;
  by: string;
  at?: string;
}

// A document attached for a requirement: its title, and the path of its newest file. The path is
// absent until a file is uploaded.
export interface AttachedDocument {
  title: string;
  path?: string;
}

// A repository path as a URL. Each segment is percent-encoded, so names with #, ? or % survive.
export function toFileUrl(path: string): string {
  return path.split("/").map(encodeURIComponent).join("/");
}

export interface SubmissionForm {
  path: string;
  title: string;
  // Whether the reader may still change the submission: they raised it, and it is still a draft
  editable: boolean;
  requirements: Requirement[];
}

export function isQuestion(item: FormItem): item is FormQuestion {
  return item.type === QUESTION;
}

export function isFormRequirement(requirement: Requirement): requirement is FormRequirement {
  return requirement.type === FORM_REQUIREMENT_TYPE;
}

export function isDocumentRequirement(requirement: Requirement): requirement is DocumentRequirement {
  return requirement.type === DOCUMENT_REQUIREMENT_TYPE;
}

export function isApprovalRequirement(requirement: Requirement): requirement is ApprovalRequirement {
  return requirement.type === APPROVAL_REQUIREMENT_TYPE;
}

// The `extractionStatus` of a submission whose documents are being read for answers
export const READING_STATUS = "running";

// Whether the submission's documents are being read for answers, from its `extractionStatus`. Until the
// reading ends, the submitter cannot change the answers and the request is not ready to send.
export function isBeingRead(extractionStatus: unknown): boolean {
  return extractionStatus === READING_STATUS;
}

// What an empty document slot says. An optional one says so, or it reads as a gap.
export function describeNothingAttached(requirement: DocumentRequirement): string {
  return requirement.required ? "Nothing attached yet" : "Nothing attached yet — optional";
}

// Whether the question must be answered: its minimum is positive.
export function isRequired(question: FormQuestion): boolean {
  return question.minAnswers > 0;
}

// Whether more than one value may be given: its maximum is not 1.
export function isMultiple(question: FormQuestion): boolean {
  return question.maxAnswers !== 1;
}

// The most values an answer takes, or undefined when it takes any number.
export function answerCap(question: FormQuestion): number | undefined {
  return question.maxAnswers > 0 ? question.maxAnswers : undefined;
}

// How many values a question taking several wants, in sentences starting with `verb`, such as
// "Choose at least 2. Choose up to 3."; empty when it takes any number.
export function countGuidance(question: FormQuestion, verb: string): string {
  const cap = answerCap(question);
  return [
    question.minAnswers > 1 ? `${verb} at least ${question.minAnswers}.` : null,
    cap !== undefined && cap > 1 ? `${verb} up to ${cap}.` : null,
  ].filter(Boolean).join(" ");
}

// Reads the form of a submission: what its schema asks, and the answers it holds, leaving out what
// does not apply now.
export async function fetchForm(doFetch: AuthenticatedFetch, path: string): Promise<SubmissionForm> {
  const response = await doFetch(`${path}.form.json`);
  if (!response.ok) {
    throw new Error(`This request could not be loaded (${response.status})`);
  }
  return await readJson<SubmissionForm>(response);
}

// Saves one answer, as a `save` event on the submission. A refusal rejects with the engine's reason.
export async function saveAnswer(
  doFetch: AuthenticatedFetch, path: string, question: string, values: string[]): Promise<void> {
  const body = new URLSearchParams();
  // Several values repeat the question's parameter
  values.forEach(value => body.append(question, value));
  if (values.length === 0) {
    // A cleared answer names the question with one empty value. The handler changes only the
    // questions the payload names, and drops the blank values.
    body.append(question, "");
  }
  const response = await doFetch(`${path}.save.json`, { method: "POST", body });
  if (!response.ok) {
    const refusal = (await response.json().catch(() => ({}))) as { error?: string };
    throw new Error(refusal.error ?? `This answer could not be saved (${response.status})`);
  }
}

// Attaches a file for a requirement, named by its node name or its path, as an `attachDocument`
// event on the submission. A file for a requirement that already has one becomes a new version of
// its document. A refusal rejects with the engine's reason.
//
// The request sets no `Content-Type`. The browser sets it, with the multipart boundary it generated.
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
