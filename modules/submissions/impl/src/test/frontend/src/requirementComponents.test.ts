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
  STORE_KEY,
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
const Other = (() => null) as RequirementComponent;

describe("the requirement component registry", () => {
  beforeEach(() => {
    clearRequirementComponents();
  });

  afterEach(() => {
    clearRequirementComponents();
  });

  it("has nothing to offer until something registers", () => {
    expect(getRequirementComponent(requirement())).toBeNull();
  });

  it("picks the candidate that is most confident", () => {
    registerRequirementComponent(() => [ Stub, 10 ]);
    registerRequirementComponent(() => [ Other, 60 ]);
    registerRequirementComponent(() => [ Stub, 40 ]);

    expect(getRequirementComponent(requirement())).toBe(Other);
  });

  it("leaves a tie to whichever registered first", () => {
    registerRequirementComponent(() => [ Stub, 50 ]);
    registerRequirementComponent(() => [ Other, 50 ]);

    expect(getRequirementComponent(requirement())).toBe(Stub);
  });

  it("passes over the candidates that do not recognize the requirement", () => {
    registerRequirementComponent(() => null);
    registerRequirementComponent(candidate =>
      (candidate.type === DOCUMENT_REQUIREMENT_TYPE ? [ Stub, 50 ] : null));

    expect(getRequirementComponent(requirement())).toBeNull();
    expect(getRequirementComponent(requirement({ type: DOCUMENT_REQUIREMENT_TYPE }))).toBe(Stub);
  });

  // A copy of this module from another build reads the same candidates, because they live on the window
  it("keeps its candidates where a second copy of this module finds them", () => {
    registerRequirementComponent(() => [ Stub, 50 ]);

    expect((window as unknown as Record<string, unknown[]>)[STORE_KEY]).toHaveLength(1);
  });

  it("offers nothing for a kind nobody claimed", () => {
    registerBuiltinRequirementComponents();

    expect(getRequirementComponent(requirement({ type: "datareq/DataRequirement" }))).toBeNull();
  });

  describe("the kinds that ship with this module", () => {
    it("draws each kind with the component that answers it", () => {
      registerBuiltinRequirementComponents();

      expect(getRequirementComponent(requirement())).toBe(QuestionSet);
      expect(getRequirementComponent(requirement({ type: DOCUMENT_REQUIREMENT_TYPE }))).toBe(DocumentSection);
      expect(getRequirementComponent(requirement({ type: APPROVAL_REQUIREMENT_TYPE }))).toBe(ApprovalSection);
    });

    it("holds one registration however often the same candidate is offered", () => {
      registerBuiltinRequirementComponents();
      registerBuiltinRequirementComponents();

      expect((window as unknown as Record<string, unknown[]>)[STORE_KEY]).toHaveLength(3);
    });
  });
});
