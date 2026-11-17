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
  COMPLETE_STEP,
  CONFIRM_STEP,
  UPLOAD_STEP,
  areDocumentsSent,
  describeWhyNotNext,
  getMissingDocuments,
  getStartingStep,
  getStep,
  isCategoryConfirmed,
} from "@iap/submissions/intake";
import {
  APPROVAL_REQUIREMENT_TYPE,
  DOCUMENT_REQUIREMENT_TYPE,
  type DocumentRequirement,
  type ExtractionState,
  FORM_REQUIREMENT_TYPE,
  type FormQuestion,
  type FormRequirement,
  QUESTION,
  SECTION,
  type SubmissionForm,
} from "@iap/submissions/submissionForm";

function document(name: string, label: string, required: boolean, attached = 0): DocumentRequirement {
  return {
    name, label, path: `/s/v1/${name}`, type: DOCUMENT_REQUIREMENT_TYPE, required, acceptedFileTypes: [],
    attached: Array.from({ length: attached }, (_, i) => ({ title: `${name}-${i}` })),
  };
}

function question(name: string, value: string[] = [], overrides: Partial<FormQuestion> = {}): FormQuestion {
  return {
    name, type: QUESTION, path: `form/${name}`, text: name, dataType: "text", minAnswers: 1, maxAnswers: 1,
    options: [], value, ...overrides,
  };
}

function categoryForm(kind: FormQuestion): FormRequirement {
  return { name: "category", label: "Kind of study", path: "/s/v1/category", type: FORM_REQUIREMENT_TYPE,
    confirmsCategory: true, items: [ kind ] };
}

function studyForm(...items: FormQuestion[]): FormRequirement {
  // Inside a section, so the questions are found at any depth
  return { name: "study", label: "The study", path: "/s/v1/study", type: FORM_REQUIREMENT_TYPE, items: [
    { name: "about", type: SECTION, label: "About", items },
  ] };
}

function form(extraction?: ExtractionState, ...requirements: SubmissionForm["requirements"]): SubmissionForm {
  return { path: "/Submissions/x", title: "A study", editable: true, readsDocuments: true, requirements,
    extraction };
}

describe("getStep", () => {
  it("shows the documents first, the category second, and everything else last", () => {
    expect(getStep(document("proposal", "research proposal", true))).toBe(UPLOAD_STEP);
    expect(getStep(categoryForm(question("kind")))).toBe(CONFIRM_STEP);
    expect(getStep(studyForm(question("title")))).toBe(COMPLETE_STEP);
    expect(getStep({ name: "review", label: "Review", path: "/s/v1/review", type: APPROVAL_REQUIREMENT_TYPE }))
      .toBe(COMPLETE_STEP);
  });
});

describe("getMissingDocuments", () => {
  it("lists the required documents with nothing uploaded", () => {
    const proposal = document("proposal", "research proposal", true);
    const consent = document("consent", "consent form", true, 1);
    const preamble = document("preamble", "preamble", false);

    expect(getMissingDocuments(form(undefined, proposal, consent, preamble))).toEqual([ proposal ]);
  });
});

describe("isCategoryConfirmed", () => {
  it("waits for an answer to every required question of a category form", () => {
    expect(isCategoryConfirmed(form(undefined, categoryForm(question("kind"))))).toBe(false);
    expect(isCategoryConfirmed(form(undefined, categoryForm(question("kind", [ "/Categories/x" ]))))).toBe(true);
    expect(isCategoryConfirmed(form(undefined, categoryForm(question("kind", [], { minAnswers: 0 })))))
      .toBe(true);
  });

  it("does not wait on the questions of other forms", () => {
    expect(isCategoryConfirmed(form(undefined, studyForm(question("title"))))).toBe(true);
  });
});

describe("areDocumentsSent", () => {
  it("locks the uploads once they were sent, unless a parse failed", () => {
    expect(areDocumentsSent(form())).toBe(false);
    expect(areDocumentsSent(form({ status: "running" }))).toBe(true);
    expect(areDocumentsSent(form({ status: "failed", retryable: true }))).toBe(false);
  });
});

describe("getStartingStep", () => {
  const read = question("title", [], { provenance: {} as FormQuestion["provenance"] });

  it("opens on the uploads until they were sent", () => {
    expect(getStartingStep(form(undefined, studyForm(read)))).toBe(UPLOAD_STEP);
  });

  it("opens on the confirmation until a form was read", () => {
    expect(getStartingStep(form({ status: "done" }, studyForm(question("title"))))).toBe(CONFIRM_STEP);
  });

  it("opens on the rest once a form was read", () => {
    expect(getStartingStep(form({ status: "done" }, studyForm(read)))).toBe(COMPLETE_STEP);
  });
});

describe("describeWhyNotNext", () => {
  it("asks for each required document not uploaded yet, by its label or else its name", () => {
    const missing = form(undefined, document("proposal", "research proposal", true), document("consent", "", true));

    expect(describeWhyNotNext(missing, UPLOAD_STEP, false))
      .toBe("Please upload the research proposal. Please upload the consent.");
    expect(describeWhyNotNext(form(undefined, document("proposal", "research proposal", true, 1)),
      UPLOAD_STEP, false)).toBeUndefined();
  });

  it("waits for the checks of the documents to end well", () => {
    const category = categoryForm(question("kind", [ "/Categories/x" ]));

    expect(describeWhyNotNext(form({ status: "running" }, category), CONFIRM_STEP, true))
      .toBe("Wait until the documents have been checked.");
    expect(describeWhyNotNext(form({ status: "done", blocked: true, message: "Unable to identify the proposal" },
      category), CONFIRM_STEP, false)).toBe("Unable to identify the proposal");
    expect(describeWhyNotNext(form({ status: "failed" }, category), CONFIRM_STEP, false))
      .toBe("The documents have to be checked first.");
    expect(describeWhyNotNext(form(undefined, category), CONFIRM_STEP, false))
      .toBe("The documents have to be checked first.");
  });

  it("waits for the category to be confirmed", () => {
    expect(describeWhyNotNext(form({ status: "done" }, categoryForm(question("kind"))), CONFIRM_STEP, false))
      .toBe("Confirm the category to continue.");
    expect(describeWhyNotNext(form({ status: "done" }, categoryForm(question("kind", [ "/Categories/x" ]))),
      CONFIRM_STEP, false)).toBeUndefined();
  });
});
