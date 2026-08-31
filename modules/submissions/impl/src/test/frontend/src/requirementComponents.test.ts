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
  type RequirementComponent
} from "@iap/submissions/requirementComponents";
import {
  ApprovalSection,
  DocumentSection,
  QuestionSet,
  registerBuiltinRequirementComponents,
} from "@iap/submissions/requirements";
import {
  APPROVAL_REQUIREMENT,
  DOCUMENT_REQUIREMENT,
  FORM_REQUIREMENT,
  type FormRequirement,
} from "@iap/submissions/submissionForm";

function requirement(overrides: Partial<FormRequirement> = {}): FormRequirement {
  return {
    name: "details",
    type: FORM_REQUIREMENT,
    label: "Request details",
    items: [],
    ...overrides,
  };
}

const Stub = (() => null) as RequirementComponent;
const Other = (() => null) as RequirementComponent;

describe("the requirement component registry", () => {
  afterEach(() => {
    clearRequirementComponents();
  });

  it("has nothing to offer until something registers", () => {
    clearRequirementComponents();

    expect(getRequirementComponent(requirement())).toBeNull();
  });

  it("picks the candidate that is most confident", () => {
    clearRequirementComponents();
    registerRequirementComponent(() => [ Stub, 10 ]);
    registerRequirementComponent(() => [ Other, 60 ]);
    registerRequirementComponent(() => [ Stub, 40 ]);

    expect(getRequirementComponent(requirement())).toBe(Other);
  });

  // Registration order decides a tie, so which component wins does not depend on the order the
  // registry happens to visit its candidates in
  it("leaves a tie to whichever registered first", () => {
    clearRequirementComponents();
    registerRequirementComponent(() => [ Stub, 50 ]);
    registerRequirementComponent(() => [ Other, 50 ]);

    expect(getRequirementComponent(requirement())).toBe(Stub);
  });

  it("passes over the candidates that do not recognize the requirement", () => {
    clearRequirementComponents();
    registerRequirementComponent(() => null);
    registerRequirementComponent(candidate =>
      (candidate.type === DOCUMENT_REQUIREMENT ? [ Stub, 50 ] : null));

    expect(getRequirementComponent(requirement())).toBeNull();
    expect(getRequirementComponent(requirement({ type: DOCUMENT_REQUIREMENT }))).toBe(Stub);
  });

  // A kind this module has never heard of is exactly what the registry is for: the projection names
  // requirements by their own resource type, so one declared elsewhere arrives here intact
  it("offers nothing for a kind nobody claimed", () => {
    clearRequirementComponents();
    registerBuiltinRequirementComponents();

    expect(getRequirementComponent(requirement({ type: "datareq/DataRequirement" }))).toBeNull();
  });

  describe("the kinds that ship with this module", () => {
    it("draws each kind with the component that answers it", () => {
      clearRequirementComponents();
      registerBuiltinRequirementComponents();

      expect(getRequirementComponent(requirement())).toBe(QuestionSet);
      expect(getRequirementComponent(requirement({ type: DOCUMENT_REQUIREMENT }))).toBe(DocumentSection);
      expect(getRequirementComponent(requirement({ type: APPROVAL_REQUIREMENT }))).toBe(ApprovalSection);
    });

    // The projection leaves `items` out of a requirement that holds none, and an empty block would
    // claim the kind while showing nothing
    it("declines a set of questions that holds none", () => {
      clearRequirementComponents();
      registerBuiltinRequirementComponents();

      expect(getRequirementComponent(requirement({ items: undefined }))).toBeNull();
    });

    // Two callers each making sure their components are present must not leave two of each
    it("holds one registration however often the same candidate is offered", () => {
      clearRequirementComponents();
      registerBuiltinRequirementComponents();
      registerBuiltinRequirementComponents();

      expect(getRequirementComponent(requirement())).toBe(QuestionSet);
      // The duplicate would be invisible through the resolver, so count the candidates by making one
      // of them lose: a second copy of the question candidate would still answer after this
      registerRequirementComponent(() => [ Stub, 90 ]);
      expect(getRequirementComponent(requirement())).toBe(Stub);
    });
  });
});
