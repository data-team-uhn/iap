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

import DateAnswer from "@iap/submissions/answers/DateAnswer";
import { QUESTION, type FormQuestion } from "@iap/submissions/submissionForm";

import { ControlledAnswer } from "./controlled.fixture";

function renderDate(values: string[] = []) {
  const question: FormQuestion = {
    name: "startDate", type: QUESTION, path: "details/startDate", text: "Which day does it start?",
    dataType: "date", minAnswers: 0, maxAnswers: 1, options: [], value: [],
  };
  const onAnswered = vi.fn();
  render(<ControlledAnswer component={DateAnswer} question={question} initial={values}
    onAnswered={onAnswered} />);
  return { onAnswered, input: screen.getByLabelText(/Which day/) };
}

describe("DateAnswer", () => {
  it("asks for a day rather than for text", () => {
    expect(renderDate().input).toHaveAttribute("type", "date");
  });

  it("shows the answer already given", () => {
    expect(renderDate([ "2026-11-03" ]).input).toHaveValue("2026-11-03");
  });

  // The stored form is what a condition compares, so it must not depend on the reader's locale
  it("saves the day in the format the input stores, once the field is left", async () => {
    const { input, onAnswered } = renderDate();

    await userEvent.type(input, "2026-11-03");
    await userEvent.tab();

    expect(onAnswered).toHaveBeenCalledWith([ "2026-11-03" ]);
  });
});
