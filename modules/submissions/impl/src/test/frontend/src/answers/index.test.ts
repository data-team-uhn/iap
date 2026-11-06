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

import { getAnswerComponent } from "@iap/submissions/answerComponents";
import { loadAnswerComponents } from "@iap/submissions/answers";
// Imported rather than fetched. In a browser each of these arrives as its own asset, named by an
// extension on the point below, and registers itself as it is evaluated. Importing does the same.
import BooleanAnswer from "@iap/submissions/answers/BooleanAnswer";
import ChoiceAnswer from "@iap/submissions/answers/ChoiceAnswer";
import DateAnswer from "@iap/submissions/answers/DateAnswer";
import NumberAnswer from "@iap/submissions/answers/NumberAnswer";
import TextAnswer from "@iap/submissions/answers/TextAnswer";
import { QUESTION, type FormQuestion } from "@iap/submissions/submissionForm";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(() => Promise.resolve([])),
}));

const mockedLoadExtensions = vi.mocked(loadExtensions);

function question(overrides: Partial<FormQuestion> = {}): FormQuestion {
  return {
    name: "duration",
    type: QUESTION,
    path: "details/duration",
    text: "How long?",
    dataType: "text",
    minAnswers: 0,
    maxAnswers: 1,
    options: [],
    value: [],
    ...overrides,
  };
}

describe("loading the answer components", () => {
  // Spelled out rather than read from the module: it is the name of the extension point the content
  // declares
  it("asks the repository which ones are declared on the AnswerComponent point", async () => {
    await loadAnswerComponents();

    expect(mockedLoadExtensions).toHaveBeenCalledWith("AnswerComponent");
  });
});

// Nothing here empties the registry. A component registers itself when its module is evaluated, which
// happens once, so a registry cleared mid-file could not be filled again.
describe("the answer components that ship with this module", () => {
  it("has one for every data type a schema can declare", () => {
    expect(getAnswerComponent(question({ dataType: "text" }))).toBe(TextAnswer);
    expect(getAnswerComponent(question({ dataType: "boolean" }))).toBe(BooleanAnswer);
    expect(getAnswerComponent(question({ dataType: "date" }))).toBe(DateAnswer);
    expect(getAnswerComponent(question({ dataType: "long" }))).toBe(NumberAnswer);
    expect(getAnswerComponent(question({ dataType: "double" }))).toBe(NumberAnswer);
  });

  it("answers a question offering options by picking, whatever its data type says", () => {
    expect(getAnswerComponent(question({
      dataType: "text",
      options: [ { value: "half-day", label: "Half day" } ],
    }))).toBe(ChoiceAnswer);
  });

  it("answers a question offering an empty list of options by its data type", () => {
    expect(getAnswerComponent(question({ dataType: "date", options: [] }))).toBe(DateAnswer);
  });

  it("offers nothing for a data type none of them recognizes", () => {
    expect(getAnswerComponent(question({ dataType: "invented" }))).toBeNull();
  });
});
