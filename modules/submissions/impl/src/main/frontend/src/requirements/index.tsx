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

import ApprovalState from "../ApprovalState";
import DocumentUpload from "../DocumentUpload";
import {
  registerRequirementComponent,
  type RequirementComponent,
  type RequirementComponentCandidate,
} from "../requirementComponents";
import { APPROVAL_REQUIREMENT, DOCUMENT_REQUIREMENT, FORM_REQUIREMENT } from "../submissionForm";
import Items from "./Items";

// The kinds of requirement this module declares. Each is a thin reading of the projection into the
// component that already draws it, so that what the editor renders is chosen by the registry rather
// than by the editor knowing the kinds.

export const QuestionSet: RequirementComponent = ({ requirement, disabled, states, onAnswered }) => (
  <Items items={requirement.items ?? []} disabled={disabled} states={states} onAnswered={onAnswered} />
);

export const DocumentSection: RequirementComponent = ({ path, requirement, disabled, onChanged }) => (
  <DocumentUpload path={path} requirement={requirement} disabled={disabled} onAttached={onChanged} />
);

export const ApprovalSection: RequirementComponent = ({ requirement }) => (
  <ApprovalState requirement={requirement} />
);

// A set of questions with no questions in it is not something this can draw: the projection omits
// `items` only for a requirement that holds none, and an empty block would claim the kind while
// showing nothing. Declining leaves the editor to say so.
export const questionSetCandidate: RequirementComponentCandidate = requirement =>
  requirement.type === FORM_REQUIREMENT && requirement.items ? [ QuestionSet, 50 ] : null;

export const documentSectionCandidate: RequirementComponentCandidate = requirement =>
  requirement.type === DOCUMENT_REQUIREMENT ? [ DocumentSection, 50 ] : null;

export const approvalSectionCandidate: RequirementComponentCandidate = requirement =>
  requirement.type === APPROVAL_REQUIREMENT ? [ ApprovalSection, 50 ] : null;

export function registerBuiltinRequirementComponents(): void {
  [
    questionSetCandidate,
    documentSectionCandidate,
    approvalSectionCandidate,
  ].forEach(registerRequirementComponent);
}
