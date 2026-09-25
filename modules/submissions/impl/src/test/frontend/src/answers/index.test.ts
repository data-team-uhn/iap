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
import { ANSWER_COMPONENT_POINT, loadAnswerComponents } from "@iap/submissions/answers";
// Imported rather than fetched: in a browser each of these arrives as its own asset, named by an
// extension on the point below, and registers itself as it is evaluated. Importing does the same
// thing, and is the only way to get them here.
import BooleanAnswer from "@iap/submissions/answers/BooleanAnswer";
import ChoiceAnswer from "@iap/submissions/answers/ChoiceAnswer";
import DateAnswer from "@iap/submissions/answers/DateAnswer";
import FileAnswer from "@iap/submissions/answers/FileAnswer";
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
    required: false,
    multiple: false,
    options: [],
    value: [],
    ...overrides,
  };
}

describe("loading the answer components", () => {
  it("asks the repository which ones are declared, rather than naming them", async () => {
    await loadAnswerComponents();

    expect(mockedLoadExtensions).toHaveBeenCalledWith(ANSWER_COMPONENT_POINT);
  });

  // Every field on a form asks, and the answer cannot change while the page lives, so asking twice
  // must not fetch twice
  it("loads them once however many times it is asked", async () => {
    await loadAnswerComponents();
    const after = mockedLoadExtensions.mock.calls.length;
    await loadAnswerComponents();

    expect(mockedLoadExtensions.mock.calls).toHaveLength(after);
  });
});

// Nothing here empties the registry, and it must not: a component registers itself as it loads, and
// a module is evaluated once, so a registry cleared mid-file could never be filled again.
describe("the answer components that ship with this module", () => {
  it("has one for every data type a schema can declare", () => {
    expect(getAnswerComponent(question({ dataType: "text" }))).toBe(TextAnswer);
    expect(getAnswerComponent(question({ dataType: "boolean" }))).toBe(BooleanAnswer);
    expect(getAnswerComponent(question({ dataType: "date" }))).toBe(DateAnswer);
    expect(getAnswerComponent(question({ dataType: "long" }))).toBe(NumberAnswer);
    expect(getAnswerComponent(question({ dataType: "double" }))).toBe(NumberAnswer);
    expect(getAnswerComponent(question({ dataType: "file" }))).toBe(FileAnswer);
  });

  // The one candidate that reads something other than the data type, and it has to win against the
  // component that would otherwise have the submitter type the answer in
  it("answers a question offering options by picking, whatever its data type says", () => {
    expect(getAnswerComponent(question({
      dataType: "text",
      options: [ { value: "half-day", label: "Half day" } ],
    }))).toBe(ChoiceAnswer);
  });

  // A deployment whose schema declares a type nothing here answers must hear about it rather than
  // be given some default input that stores a value the schema will not accept
  it("offers nothing for a data type none of them recognizes", () => {
    expect(getAnswerComponent(question({ dataType: "invented" }))).toBeNull();
  });
});
