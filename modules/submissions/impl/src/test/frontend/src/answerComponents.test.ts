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
  type AnswerComponent,
  type AnswerComponentCandidate
} from "@iap/submissions/answerComponents";
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

  // Counted rather than resolved. A duplicate answers with the same component at the same
  // confidence, so what it changes is not which component wins but how often one is asked, and
  // asserting on the winner would hold whether or not anything deduplicated
  it("asks a candidate offered twice only once", () => {
    clearAnswerComponents();
    const candidate = vi.fn<AnswerComponentCandidate>(() => [ Stub, 90 ]);
    registerAnswerComponent(candidate);
    registerAnswerComponent(candidate);

    expect(getAnswerComponent(question())).toBe(Stub);
    expect(candidate).toHaveBeenCalledTimes(1);
  });
});
