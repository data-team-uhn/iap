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

import {
  type DocumentRequirement,
  type FormItem,
  type FormQuestion,
  type Requirement,
  type SubmissionForm,
  describeBlock,
  isDocumentRequirement,
  isFormRequirement,
  isQuestion,
  isRequired,
} from "./submissionForm";

// The steps of a request whose documents a model reads: upload them, confirm what the model decided
// about them, then complete the rest, which by then is read from them.
export const UPLOAD_STEP = 0;
export const CONFIRM_STEP = 1;
export const COMPLETE_STEP = 2;

export const STEP_LABELS = [ "Upload the documents", "Confirm", "Complete the request" ] as const;

// Which step shows a requirement
export function getStep(requirement: Requirement): number {
  if (isDocumentRequirement(requirement)) {
    return UPLOAD_STEP;
  }
  return isFormRequirement(requirement) && requirement.confirmsCategory === true ? CONFIRM_STEP : COMPLETE_STEP;
}

// The required documents nothing was uploaded for yet
export function getMissingDocuments(form: SubmissionForm): DocumentRequirement[] {
  return form.requirements.filter(isDocumentRequirement)
    .filter(document => document.required && document.attached.length === 0);
}

function getQuestions(items: FormItem[]): FormQuestion[] {
  return items.flatMap(item => isQuestion(item) ? [ item ] : getQuestions(item.items));
}

// Whether the submitter answered the category the model suggested, in each form that holds one
export function isCategoryConfirmed(form: SubmissionForm): boolean {
  return form.requirements.filter(isFormRequirement)
    .filter(requirement => requirement.confirmsCategory === true)
    .flatMap(requirement => getQuestions(requirement.items))
    .every(question => !isRequired(question) || question.value.length > 0);
}

// Whether the uploads were sent to be read, so a new one would not be read: the intake reads each
// document once. A failed parse is the exception, since sending it again is how it is fixed.
export function areDocumentsSent(form: SubmissionForm): boolean {
  return form.extraction !== undefined && form.extraction.retryable !== true;
}

// The step to open on: the uploads until they were sent, the rest once a form was read from them,
// and what the model decided in between
export function getStartingStep(form: SubmissionForm): number {
  if (form.extraction === undefined) {
    return UPLOAD_STEP;
  }
  const read = form.requirements.filter(isFormRequirement)
    .filter(requirement => getStep(requirement) === COMPLETE_STEP)
    .flatMap(requirement => getQuestions(requirement.items))
    .some(question => question.provenance !== undefined);
  return read ? COMPLETE_STEP : CONFIRM_STEP;
}

// What stops the submitter from going on to the next step, or undefined when nothing does
export function describeWhyNotNext(form: SubmissionForm, step: number, reading: boolean): string | undefined {
  if (step === UPLOAD_STEP) {
    const missing = getMissingDocuments(form);
    return missing.length > 0
      ? missing.map(document => `Please upload the ${document.label || document.name}.`).join(" ")
      : undefined;
  }
  if (reading) {
    return "Wait until the documents have been checked.";
  }
  const block = describeBlock(form.extraction);
  if (block !== undefined) {
    return block;
  }
  if (form.extraction?.status !== "done") {
    return "The documents have to be checked first.";
  }
  return isCategoryConfirmed(form) ? undefined : "Confirm the category to continue.";
}
