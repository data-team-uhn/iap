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

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import NumberAnswer from "@iap/submissions/answers/NumberAnswer";
import { QUESTION, type FormQuestion } from "@iap/submissions/submissionForm";

import { ControlledAnswer } from "./controlled.fixture";

function renderNumber(dataType: string, values: string[] = [], overrides: Partial<FormQuestion> = {}) {
  const question: FormQuestion = {
    name: "days", type: QUESTION, path: "details/days", text: "How many days?",
    dataType, minAnswers: 0, maxAnswers: 1, options: [], value: [], ...overrides,
  };
  const onAnswered = vi.fn();
  render(<ControlledAnswer component={NumberAnswer} question={question} initial={values}
    onAnswered={onAnswered} />);
  return { onAnswered, input: screen.getByLabelText(/How many days/) };
}

describe("NumberAnswer", () => {
  // The schema asking for a long rather than a double is a statement about the answer, and the input
  // is where it should be enforced — not in a save that comes back refused
  it("steps in whole numbers for a long", () => {
    const { input } = renderNumber("long");

    expect(input).toHaveAttribute("type", "number");
    expect(input).toHaveAttribute("step", "1");
    expect(input).toHaveAttribute("inputmode", "numeric");
  });

  it("lets a double be a decimal", () => {
    const { input } = renderNumber("double");

    expect(input).toHaveAttribute("step", "any");
    expect(input).toHaveAttribute("inputmode", "decimal");
  });

  // The save is what enforces the bounds; here they only become the input's own hints
  it("carries the schema's bounds onto the input", () => {
    const { input } = renderNumber("long", [], { minValue: 1, maxValue: 30 });

    expect(input).toHaveAttribute("min", "1");
    expect(input).toHaveAttribute("max", "30");
  });

  it("leaves the input unbounded where the schema is", () => {
    const { input } = renderNumber("long");

    expect(input).not.toHaveAttribute("min");
    expect(input).not.toHaveAttribute("max");
  });

  it("shows the answer already given", () => {
    expect(renderNumber("long", [ "3" ]).input).toHaveValue(3);
  });

  it("saves when the field is left, not while typing", async () => {
    const { input, onAnswered } = renderNumber("long");

    await userEvent.type(input, "12");
    // What was typed is shown, but nothing has been saved yet: an answer is finished by leaving it
    expect(input).toHaveValue(12);
    expect(onAnswered).not.toHaveBeenCalled();

    await userEvent.tab();
    expect(onAnswered).toHaveBeenCalledWith([ "12" ]);
  });

  // Clearing a field means there is no answer, not that the answer is the empty string
  it("stores nothing at all when the field is cleared", async () => {
    const { input, onAnswered } = renderNumber("long", [ "3" ]);

    await userEvent.clear(input);
    await userEvent.tab();

    expect(onAnswered).toHaveBeenCalledWith([]);
  });
});
