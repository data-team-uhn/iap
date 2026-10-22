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
import { createExtensionLoader } from "@iap/ui-extension/extensionLoader";

import ApprovalState from "../ApprovalState";
import DocumentUpload from "../DocumentUpload";
import GeneratedDocument from "../GeneratedDocument";
import {
  registerRequirementComponent,
  type RequirementComponent,
  type RequirementComponentCandidate,
} from "../requirementComponents";
import {
  type ApprovalRequirement,
  type DocumentRequirement,
  type FormRequirement,
  isApprovalRequirement,
  isDocumentRequirement,
  isFormRequirement,
} from "../submissionForm";
import Items from "./Items";

// The components for the kinds of requirement this module declares. Each casts the requirement
// unchecked, because its candidate has already checked the kind.

export const QuestionSet: RequirementComponent = ({ requirement, disabled, states, onAnswered }) => (
  <Items items={(requirement as FormRequirement).items} disabled={disabled} states={states} onAnswered={onAnswered} />
);

export const DocumentSection: RequirementComponent = ({ path, requirement, disabled, onChanged }) => (
  <DocumentUpload path={path} requirement={requirement as DocumentRequirement} disabled={disabled}
    onAttached={onChanged} />
);

export const GeneratedDocumentSection: RequirementComponent = ({ path, requirement, disabled, onChanged }) => (
  <GeneratedDocument path={path} requirement={requirement as DocumentRequirement} disabled={disabled}
    onGenerated={onChanged} />
);

export const ApprovalSection: RequirementComponent = ({ requirement }) => (
  <ApprovalState requirement={requirement as ApprovalRequirement} />
);

export const questionSetCandidate: RequirementComponentCandidate = requirement =>
  isFormRequirement(requirement) ? [ QuestionSet, 50 ] : null;

export const documentSectionCandidate: RequirementComponentCandidate = requirement =>
  isDocumentRequirement(requirement) ? [ DocumentSection, 50 ] : null;

// Above the upload, since the platform fills a generated document in and there is nothing to upload
export const generatedDocumentCandidate: RequirementComponentCandidate = requirement =>
  isDocumentRequirement(requirement) && requirement.generated ? [ GeneratedDocumentSection, 60 ] : null;

export const approvalSectionCandidate: RequirementComponentCandidate = requirement =>
  isApprovalRequirement(requirement) ? [ ApprovalSection, 50 ] : null;

export function registerBuiltinRequirementComponents(): void {
  [
    questionSetCandidate,
    documentSectionCandidate,
    generatedDocumentCandidate,
    approvalSectionCandidate,
  ].forEach(registerRequirementComponent);
}

// Where other modules declare the components for the kinds of requirement they add
export const REQUIREMENT_COMPONENT_POINT = "RequirementComponent";

const loader = createExtensionLoader(REQUIREMENT_COMPONENT_POINT, "requirement components");

// Loads every requirement component other modules declare, once
export const loadRequirementComponents = loader.load;

// Whether the requirement components other modules declare are available yet
export const useRequirementComponents = loader.useLoaded;
