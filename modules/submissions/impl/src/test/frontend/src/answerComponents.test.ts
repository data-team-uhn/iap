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
  clearAnswerComponents,
  getAnswerComponent,
  registerAnswerComponent,
  type AnswerComponent
} from "@iap/submissions/answerComponents";
import { registerBuiltinAnswerComponents } from "@iap/submissions/answers";
import ChoiceAnswer from "@iap/submissions/answers/ChoiceAnswer";
import TextAnswer from "@iap/submissions/answers/TextAnswer";
import { QUESTION, type FormQuestion } from "@iap/submissions/submissionForm";

function question(overrides: Partial<FormQuestion> = {}): FormQuestion {
  return {
    name: "duration",
    type: QUESTION,
    path: "details/duration",
    text: "How long?",
    dataType: "text",
    required: false,
    multiple: false,
    options: [],
    value: [],
    ...overrides,
  };
}

const Stub = (() => null) as AnswerComponent;
const Other = (() => null) as AnswerComponent;

describe("the answer component registry", () => {
  afterEach(() => {
    clearAnswerComponents();
  });

  it("has nothing to offer until something registers", () => {
    clearAnswerComponents();

    expect(getAnswerComponent(question())).toBeNull();
  });

  it("picks the candidate that is most confident", () => {
    clearAnswerComponents();
    registerAnswerComponent(() => [ Stub, 10 ]);
    registerAnswerComponent(() => [ Other, 60 ]);
    registerAnswerComponent(() => [ Stub, 40 ]);

    expect(getAnswerComponent(question())).toBe(Other);
  });

  // Registration order decides a tie, so which component wins does not depend on the order the
  // registry happens to visit its candidates in
  it("leaves a tie to whichever registered first", () => {
    clearAnswerComponents();
    registerAnswerComponent(() => [ Stub, 50 ]);
    registerAnswerComponent(() => [ Other, 50 ]);

    expect(getAnswerComponent(question())).toBe(Stub);
  });

  it("passes over the candidates that do not recognize the question", () => {
    clearAnswerComponents();
    registerAnswerComponent(() => null);
    registerAnswerComponent(candidate => (candidate.dataType === "date" ? [ Stub, 50 ] : null));

    expect(getAnswerComponent(question({ dataType: "text" }))).toBeNull();
    expect(getAnswerComponent(question({ dataType: "date" }))).toBe(Stub);
  });

  describe("the components that ship with this module", () => {
    it("answers a question offering options by picking, whatever its data type says", () => {
      clearAnswerComponents();
      registerBuiltinAnswerComponents();

      expect(getAnswerComponent(question({
        dataType: "text",
        options: [ { value: "half-day", label: "Half day" } ],
      }))).toBe(ChoiceAnswer);
      expect(getAnswerComponent(question({ dataType: "text" }))).toBe(TextAnswer);
    });

    // Two callers each making sure their components are present must not leave two of each
    it("holds one registration however often the same candidate is offered", () => {
      clearAnswerComponents();
      registerBuiltinAnswerComponents();
      registerBuiltinAnswerComponents();

      expect(getAnswerComponent(question())).toBe(TextAnswer);
      // The duplicate would be invisible through the resolver, so count the candidates by making one
      // of them lose: a second copy of the text candidate would still answer after this
      registerAnswerComponent(() => [ Stub, 90 ]);
      expect(getAnswerComponent(question())).toBe(Stub);
    });
  });
});
