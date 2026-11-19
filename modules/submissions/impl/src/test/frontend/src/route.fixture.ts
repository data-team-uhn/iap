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
  type AttachedDocument,
  DOCUMENT_REQUIREMENT_TYPE,
  type DocumentRequirement,
  FORM_REQUIREMENT_TYPE,
  type FormQuestion,
  type FormRequirement,
  QUESTION,
  type SubmissionForm,
} from "@iap/submissions/submissionForm";

// A request whose workflow first asks whether there is a protocol: with one, the protocol is uploaded and
// read; without one, a questionnaire is answered and the proposal written from it.

export const SUBMISSION = "/Submissions/ab/cd/ef/0a1b2c3d-0000-0000-0000-000000000000";

const VERSION = "/Schemas/researchProposal/v1";

export function hasProtocol(value: string[] = []): FormQuestion {
  return {
    name: "hasProtocol", type: QUESTION, path: "protocol/hasProtocol", text: "Do you have a study protocol?",
    dataType: "boolean", minAnswers: 1, maxAnswers: 1, options: [], value,
  };
}

export function protocol(value: string[] = []): FormRequirement {
  return {
    name: "protocol", path: `${VERSION}/protocol`, type: FORM_REQUIREMENT_TYPE, label: "Study protocol",
    items: [ hasProtocol(value) ],
  };
}

export const CHOICE = protocol([ "true" ]);

export function questionnaire(value: string[] = []): FormRequirement {
  return {
    name: "questionnaire", path: `${VERSION}/questionnaire`, type: FORM_REQUIREMENT_TYPE, label: "Research plan",
    items: [ {
      name: "studyTitle", type: QUESTION, path: "questionnaire/studyTitle", text: "What is the study called?",
      dataType: "text", minAnswers: 1, maxAnswers: 1, options: [], value,
    } ],
  };
}

export const QUESTIONNAIRE = questionnaire([ "Outcomes after transplant" ]);

function documentRequirement(name: string, generated: boolean): DocumentRequirement {
  return {
    name, path: `${VERSION}/${name}`, type: DOCUMENT_REQUIREMENT_TYPE, label: name, required: !generated, generated,
    acceptedFileTypes: [], attached: [],
  };
}

export const PROPOSAL = documentRequirement("proposal", false);

export const GENERATED = documentRequirement("generatedProposal", true);

// A document requirement holding the given versions
export function attaching(requirement: DocumentRequirement, ...attached: AttachedDocument[]): DocumentRequirement {
  return { ...requirement, attached };
}

// The version the platform wrote from the answers
export const WRITTEN = {
  title: "Research plan.pdf",
  path: `${SUBMISSION}/generatedProposal/v1/file/pdfFile`,
  docx: `${SUBMISSION}/generatedProposal/v1/file/generated.docx`,
};

export function routeForm(overrides: Partial<SubmissionForm> = {}): SubmissionForm {
  return { path: SUBMISSION, title: "A study", editable: true, requirements: [ CHOICE ], ...overrides };
}
