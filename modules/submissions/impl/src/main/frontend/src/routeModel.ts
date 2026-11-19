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

// A request whose workflow opens by asking the submitter a question, and takes its way from the answer.
// The editor keeps that question on show, and shows the step the workflow waits for under it. No React,
// no fetch.

import { offers } from "@iap/frontend-commons/workflowEvents";

import {
  type AttachedDocument,
  type FormQuestion,
  type FormRequirement,
  type Requirement,
  type SubmissionForm,
  isDocumentRequirement,
  isFormRequirement,
} from "./submissionForm";
import { COMPLETE_EVENT, type SubmissionTask } from "./taskModel";

// Where the request is on its way, which decides what the editor shows under the choice:
// - choose: the first step is open, and its Next takes the way the answer picked;
// - read: the way with documents to read, filled in by the reading steps;
// - fill: a step asks for a form, and its Next goes on;
// - send: anything else, such as the step that sends the request, so everything is shown.
export type RouteStage =
  | { name: "choose"; step: SubmissionTask }
  | { name: "read"; step?: SubmissionTask }
  | { name: "fill"; step: SubmissionTask; asked: FormRequirement }
  | { name: "send" };

// The step the submitter can take now: a task offered to them with nothing to decide
export function getOpenStep(tasks: SubmissionTask[]): SubmissionTask | undefined {
  return tasks.find(task => offers(task, COMPLETE_EVENT) && task.outcomeOptions.length === 0);
}

// The form a step asks for, by its name, when the form shows it. The workflow's first step names the choice.
export function getChoice(form: SubmissionForm, name: string | undefined): FormRequirement | undefined {
  return form.requirements.filter(isFormRequirement).find(requirement => requirement.name === name);
}

export function getStage(form: SubmissionForm, choice: FormRequirement, step: SubmissionTask | undefined): RouteStage {
  if (step?.requirement === choice.name) {
    return { name: "choose", step };
  }
  if (form.readsDocuments === true) {
    return { name: "read", step };
  }
  const asked = getChoice(form, step?.requirement);
  return step !== undefined && asked !== undefined ? { name: "fill", step, asked } : { name: "send" };
}

// What a stage shows under the choice. A form step leaves out what the platform writes, since its Next
// writes it.
export function getStageRequirements(form: SubmissionForm, choice: FormRequirement, stage: RouteStage): Requirement[] {
  if (stage.name === "choose") {
    return [];
  }
  const others = form.requirements.filter(requirement => requirement.name !== choice.name);
  return stage.name === "fill"
    ? others.filter(requirement => !isDocumentRequirement(requirement) || !requirement.generated)
    : others;
}

// Whether a save changes the answer: it is not blank, and differs from the saved one. Changing the
// choice's answer completes the open step, so the workflow can take the other way.
export function isNewAnswer(question: FormQuestion, values: string[]): boolean {
  return values.length > 0 && values.join("\n") !== question.value.join("\n");
}

// A version the platform wrote of a generated document: a PDF with the .docx it came from
export interface GeneratedVersion extends AttachedDocument {
  path: string;
  docx: string;
}

// The newest version the platform wrote of a generated document, unless the submitter uploaded a newer one
export function getGeneratedVersion(form: SubmissionForm): GeneratedVersion | undefined {
  return form.requirements.filter(isDocumentRequirement)
    .filter(requirement => requirement.generated)
    .flatMap(requirement => requirement.attached)
    .find((document): document is GeneratedVersion => document.path !== undefined && document.docx !== undefined);
}
