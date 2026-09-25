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
import { readJson, RequestError } from "@iap/frontend-commons/requestFailure";

import type { QuestionProvenance } from "./provenance";

// The form a submitter fills in, as the server projects it, and the one way to change it.
//
// Both halves of this are deliberately thin. What to show is decided server-side — conditions are
// resolved there, so a question absent from this document is a question that does not currently
// apply — and what a change means is decided by a workflow. Nothing here evaluates a condition or
// writes to the repository.

// The resource types the projection reports. It names the schema's own types rather than a
// vocabulary of its own, so a requirement kind added later arrives here without a release.
export const FORM_REQUIREMENT = "sch/FormRequirement";
export const DOCUMENT_REQUIREMENT = "sch/DocumentRequirement";
export const APPROVAL_REQUIREMENT = "sch/ApprovalRequirement";
export const SECTION = "sch/Section";
export const QUESTION = "sch/Question";

/**
 * A date as the reader's locale writes it. JCR dates are serialized as ISO 8601 strings; anything
 * else is not a date and formats as nothing rather than as "Invalid Date".
 */
export function formatDate(value: unknown): string {
  return typeof value === "string" && value !== "" ? new Date(value).toLocaleString() : "";
}

// One of the answers a question offers. The value is what an answer stores and what a condition
// compares against; the label is only what the submitter reads.
export interface FormAnswerOption {
  value: string;
  label: string;
  // What the option means, when the label alone does not say. A category is judged by this.
  description?: string;
}

export interface FormQuestion {
  name: string;
  type: string;
  // Where this question's answer is posted, relative to the schema version. Given by the server so
  // that only one side of the exchange decides how a question is addressed.
  path: string;
  text: string;
  description?: string;
  // Why this is being asked, in the schema author's words. Distinct from the description, which says
  // how to answer: a question the submitter did not expect is easier to answer once they know what the
  // answer is for.
  purpose?: string;
  // One of text, long, double, boolean, date, file
  dataType: string;
  // How many values an answer takes, as the schema stores it: a positive minimum is what "required"
  // means, a maximum other than 1 is what allows several values, and zero or negative leaves that
  // end unconstrained. Read through isRequired/isMultiple below rather than re-derived ad hoc.
  minAnswers: number;
  maxAnswers: number;
  // Hard bounds for numeric answers, present only where the schema states them. The save is where
  // they are enforced; here they only become the input's own hints.
  minValue?: number;
  maxValue?: number;
  // A regular expression every value must match in full, with what to tell the submitter when one
  // does not. Enforced by the save, whose refusal carries the message.
  pattern?: string;
  patternMessage?: string;
  // The answers this question offers, empty when it is answered freely. Always present, so that
  // "answered freely" is something the form states rather than something a reader infers.
  options: FormAnswerOption[];
  // Where this question comes in the order the form asks them, counted by the server over the
  // requirements that apply. Given rather than counted here because the form is shown two ways, and a
  // question that is 3 while being answered has to be 3 while being read. Optional because it is
  // additive: a form without it is shown unnumbered rather than refused.
  number?: number;
  value: string[];
  // Where a pre-filled answer came from, present only for a question the extraction answered. A
  // question the submitter answered themselves has none, which is what "nobody suggested this"
  // looks like.
  provenance?: QuestionProvenance;
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
  // Present only for document requirements: whether the submission is incomplete without it. An
  // optional one is still asked - whether it is asked at all was decided server-side - but skipping
  // it blocks nothing, and the control says so.
  required?: boolean;
  // Present only for document requirements. Empty means no restriction, which is why the key is
  // there at all: a reader has to tell "takes anything" from "takes nothing".
  acceptedFileTypes?: string[];
  // A blank to start from, where the requirement offers one
  template?: string;
  // What has been attached for a document requirement already, by title. Present so that reopening
  // the form shows a document that is there rather than an empty control implying it is not.
  attached?: string[];
  // Present only for approval requirements: the group whose members decide, empty when it is not
  // narrowed to one. Nobody answers an approval in the form, so what it can show is where it stands.
  approverGroup?: string;
  // Present only for approval requirements: whether it has been granted.
  approved?: boolean;
  // Present only for approval requirements, and only once somebody has reviewed it. A review that
  // did not approve is still a decision, so these say who and when regardless of `approved`.
  decidedBy?: string;
  decidedAt?: string;
}

// Where reading the answers out of the uploaded documents got to. `running` is the only state that
// is still going; the others stop the spinner, and `failed` comes with a reason for the person.
export interface ExtractionState {
  status: "running" | "done" | "failed";
  message?: string;
  // Whether an upload is sitting on a failed parse. It decides which half of the pipeline asking
  // again has to start from: a document the daemon never read has to go back to the daemon, while
  // one that parsed fine only needs the model asked again.
  retryable?: boolean;
  // Whether every upload has left the daemon. The parse itself reports no progress, only this end.
  parsed?: boolean;
  // Whether a job has taken the reading, which is after the parse has been read in and before the
  // model is asked. Absent on a form served before this was recorded.
  reading?: boolean;
}

export interface SubmissionForm {
  path: string;
  title: string;
  // Whether this reader may still answer: the same two rules the save workflow enforces, so the
  // editor offers editing only where a save would be accepted rather than learning from a refusal
  editable: boolean;
  // Whether anything reads the documents attached here, which is whether the schema version names a
  // reading workflow. False means the answers will never be filled in from a document, however many
  // are attached.
  readsDocuments: boolean;
  requirements: FormRequirement[];
  // Present once the documents were sent to be read; absent for a submission that never was
  extraction?: ExtractionState;
}

export function isQuestion(item: FormItem): item is FormQuestion {
  return item.type === QUESTION;
}

// Whether an answer must be provided before submitting — a reading of the answer-count pair, the
// same one the server derives, so the two sides cannot disagree about what a count means.
export function isRequired(question: FormQuestion): boolean {
  return question.minAnswers > 0;
}

// Whether more than one value may be provided — the other reading of the pair.
export function isMultiple(question: FormQuestion): boolean {
  return question.maxAnswers !== 1;
}

// Reads the form for a submission: what its schema asks, what it already answers, and nothing that
// does not currently apply.
export async function fetchForm(
  path: string,
  doFetch: (url: string, init?: RequestInit) => Promise<Response> = fetch,
): Promise<SubmissionForm> {
  const response = await doFetch(`${path}.form.json`);
  if (!response.ok) {
    throw new RequestError(response.status);
  }
  return readJson<SubmissionForm>(response);
}

// Records one answer, by posting it to the submission itself. That POST is a `save` event, matched
// by a system workflow — filling a request in is a workflow event and not a write — so a refusal
// arrives as the engine's own reason rather than as a repository error.
export async function saveAnswer(path: string, question: string, values: string[]): Promise<void> {
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
  const response = await fetch(path, { method: "POST", body });
  if (!response.ok) {
    const refusal = (await response.json().catch(() => ({}))) as { error?: string };
    throw new Error(refusal.error ?? `This answer could not be saved (${response.status})`);
  }
}

// Records what the submitter makes of a pre-filled answer, as a `reviewExtraction` event.
//
// Two verdicts, sent separately because they mean different things. Confirming settles the answer.
// Saying the passage does not support it is a report about the extraction, and settles nothing.
//
// The event is named by a selector, so `.json` has to follow it. See attachDocument below for why.
export async function reviewExtraction(path: string, question: string,
  verdict: { confirmed?: boolean; evidenceRejected?: boolean }): Promise<void> {
  const body = new URLSearchParams();
  body.append("question", question);
  if (verdict.confirmed !== undefined) {
    body.append("confirmed", String(verdict.confirmed));
  }
  if (verdict.evidenceRejected !== undefined) {
    body.append("evidenceRejected", String(verdict.evidenceRejected));
  }
  const response = await fetch(`${path}.reviewExtraction.json`, { method: "POST", body });
  if (!response.ok) {
    const refusal = (await response.json().catch(() => ({}))) as { error?: string };
    throw new Error(refusal.error ?? `This could not be recorded (${response.status})`);
  }
}

// Asks for the reading to be done over, from as far back as it has to start: `retryParse` sends the
// uploads whose parse failed to the daemon again, and `extractAnswers` asks the model again about the
// ones that parsed perfectly well.
//
// Offered beside the message saying what went wrong, because what usually goes wrong is on our side —
// the daemon down, the model refusing — and the submitter has no way of telling that from a problem
// with their own document.
//
// The event is named by a selector, so `.json` has to follow it. See attachDocument below for why.
// Stops a reading that is still going. The server drops a parse that has not finished, or cuts off
// the model call and the Markdown it was reading, and the page asks again so the spinner goes away.
export async function stopProcessing(path: string): Promise<void> {
  const response = await fetch(`${path}.stopProcessing.json`, { method: "POST" });
  if (!response.ok) {
    const refusal = (await response.json().catch(() => ({}))) as { error?: string };
    throw new Error(refusal.error ?? `The reading could not be aborted (${response.status})`);
  }
}

export async function readAgain(path: string, fromTheParse: boolean): Promise<void> {
  const event = fromTheParse ? "retryParse" : "extractAnswers";
  const response = await fetch(`${path}.${event}.json`, { method: "POST" });
  if (!response.ok) {
    const refusal = (await response.json().catch(() => ({}))) as { error?: string };
    throw new Error(refusal.error ?? `The document could not be read again (${response.status})`);
  }
}

// Attaches a file to the requirement it answers, as an `attachDocument` event on the submission —
// uploading is a workflow step for the same reason answering is, so what may be attached and until
// when is the handler's answer rather than a permission on the folder.
//
// The event is named by a *selector*, which is why `.json` follows it: in `<path>.attachDocument`
// Sling reads the last dot-separated token as the extension, so the event name would arrive as a
// format and the POST would mean `save` instead. `.json` is also what comes back.
//
// `FormData` rather than a query string, and deliberately without a `Content-Type`: the browser has
// to set it, because only it knows the multipart boundary it just generated.
export async function attachDocument(path: string, requirement: string, file: File): Promise<void> {
  const body = new FormData();
  body.append("requirement", requirement);
  body.append("file", file);
  const response = await fetch(`${path}.attachDocument.json`, { method: "POST", body });
  if (!response.ok) {
    const refusal = (await response.json().catch(() => ({}))) as { error?: string };
    throw new Error(refusal.error ?? `This file could not be attached (${response.status})`);
  }
}

// Takes the document answering a requirement back off, as a `detachDocument` event. The whole
// document goes, not its latest version: attaching twice makes a new version, which reads as a
// replacement, but removing says this was the wrong file altogether.
//
// The event is named by a selector, so `.json` has to follow it, for the reason given above.
export async function detachDocument(path: string, requirement: string): Promise<void> {
  const body = new URLSearchParams();
  body.append("requirement", requirement);
  const response = await fetch(`${path}.detachDocument.json`, { method: "POST", body });
  if (!response.ok) {
    const refusal = (await response.json().catch(() => ({}))) as { error?: string };
    throw new Error(refusal.error ?? `This file could not be removed (${response.status})`);
  }
}
