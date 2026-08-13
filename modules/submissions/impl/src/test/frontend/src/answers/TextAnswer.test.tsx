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

import TextAnswer from "@iap/submissions/answers/TextAnswer";
import { QUESTION, type FormQuestion } from "@iap/submissions/submissionForm";

import { ControlledAnswer } from "./controlled.fixture";

function renderText(overrides: Partial<FormQuestion> = {}, values: string[] = []) {
  const question: FormQuestion = {
    name: "reason", type: QUESTION, path: "details/reason", text: "Why?",
    dataType: "text", required: false, multiple: false, options: [], value: [], ...overrides,
  };
  const onAnswered = vi.fn();
  render(<ControlledAnswer component={TextAnswer} question={question} initial={values}
    onAnswered={onAnswered} />);
  return { onAnswered, input: screen.getByLabelText(/Why/) };
}

describe("TextAnswer", () => {
  it("saves when the field is left, not while typing", async () => {
    const { input, onAnswered } = renderText();

    await userEvent.type(input, "Family visit");
    expect(onAnswered).not.toHaveBeenCalled();

    await userEvent.tab();
    expect(onAnswered).toHaveBeenCalledWith([ "Family visit" ]);
  });

  it("stores nothing at all when the field is cleared", async () => {
    const { input, onAnswered } = renderText({}, [ "Family visit" ]);

    await userEvent.clear(input);
    await userEvent.tab();

    expect(onAnswered).toHaveBeenCalledWith([]);
  });

  describe("a question taking several values", () => {
    it("takes them one per line, dropping the blank ones", async () => {
      const { input, onAnswered } = renderText({ multiple: true });

      await userEvent.type(input, "Monday{Enter}{Enter}Tuesday");
      await userEvent.tab();

      expect(onAnswered).toHaveBeenCalledWith([ "Monday", "Tuesday" ]);
    });

    it("says how to give more than one", () => {
      renderText({ multiple: true, description: "The days you will be away." });

      expect(screen.getByText("The days you will be away. One per line.")).toBeInTheDocument();
    });
  });
});
