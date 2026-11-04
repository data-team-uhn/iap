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
  clearRequirementComponents,
  getRequirementComponent,
  registerRequirementComponent,
  type RequirementComponent,
  type RequirementComponentCandidate,
} from "@iap/submissions/requirementComponents";
import {
  ApprovalSection,
  DocumentSection,
  QuestionSet,
  registerBuiltinRequirementComponents,
} from "@iap/submissions/requirements";
import {
  APPROVAL_REQUIREMENT_TYPE,
  DOCUMENT_REQUIREMENT_TYPE,
  FORM_REQUIREMENT_TYPE,
  type FormRequirement,
} from "@iap/submissions/submissionForm";

function requirement(overrides: Partial<FormRequirement> = {}): FormRequirement {
  return {
    name: "details",
    path: "/Schemas/demo/v1/details",
    type: FORM_REQUIREMENT_TYPE,
    label: "Request details",
    items: [],
    ...overrides,
  };
}

const Stub = (() => null) as RequirementComponent;

describe("the requirement component registry", () => {
  afterEach(() => {
    clearRequirementComponents();
  });

  // Spelled out rather than read from the module: the name is what a separately built bundle
  // contributing a requirement kind agrees on
  it("keeps its candidates where a separately built bundle finds them", () => {
    const candidate: RequirementComponentCandidate = () => [ Stub, 50 ];

    registerRequirementComponent(candidate);

    expect((window as unknown as Record<string, unknown>).__iapRequirementComponents).toEqual([ candidate ]);
  });

  describe("the kinds that ship with this module", () => {
    it("draws each kind with the component that answers it", () => {
      registerBuiltinRequirementComponents();

      expect(getRequirementComponent(requirement())).toBe(QuestionSet);
      expect(getRequirementComponent(requirement({ type: DOCUMENT_REQUIREMENT_TYPE }))).toBe(DocumentSection);
      expect(getRequirementComponent(requirement({ type: APPROVAL_REQUIREMENT_TYPE }))).toBe(ApprovalSection);
    });

    it("offers nothing for a kind nobody claimed", () => {
      registerBuiltinRequirementComponents();

      expect(getRequirementComponent(requirement({ type: "datareq/DataRequirement" }))).toBeNull();
    });
  });
});
