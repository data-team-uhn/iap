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

import ChoiceAnswer from "@iap/submissions/answers/ChoiceAnswer";
import { QUESTION, type FormQuestion } from "@iap/submissions/submissionForm";

const OPTIONS = [
  { value: "half-day", label: "Half day" },
  { value: "full-day", label: "Full day" },
  { value: "multiple-days", label: "Several days" },
];

function question(overrides: Partial<FormQuestion> = {}): FormQuestion {
  return {
    name: "duration",
    type: QUESTION,
    path: "details/duration",
    text: "Is this a half day, a full day, or several days?",
    dataType: "text",
    minAnswers: 0,
    maxAnswers: 1,
    options: OPTIONS,
    value: [],
    ...overrides,
  };
}

function renderChoice(overrides: Partial<FormQuestion> = {}, values: string[] = []) {
  const onAnswered = vi.fn();
  render(
    <ChoiceAnswer
      question={question(overrides)}
      values={values}
      disabled={false}
      onChange={vi.fn()}
      onAnswered={onAnswered}
    />
  );
  return onAnswered;
}

describe("ChoiceAnswer", () => {
  it("offers each answer by the label it was given", () => {
    renderChoice();

    OPTIONS.forEach(option =>
      expect(screen.getByRole("radio", { name: option.label })).toBeInTheDocument());
  });

  // The label is what the submitter reads; the value is what an answer means, and what a condition
  // compares against — so rewording a label must not change any answer already recorded
  it("stores the value behind the label, not the label", async () => {
    const onAnswered = renderChoice();

    await userEvent.click(screen.getByRole("radio", { name: "Several days" }));

    expect(onAnswered).toHaveBeenCalledWith([ "multiple-days" ]);
  });

  it("shows the answer already given", () => {
    renderChoice({}, [ "full-day" ]);

    expect(screen.getByRole("radio", { name: "Full day" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "Half day" })).not.toBeChecked();
  });

  it("describes the question when the schema explains it", () => {
    renderChoice({ description: "Pick the one that fits." });

    expect(screen.getByText("Pick the one that fits.")).toBeInTheDocument();
  });

  describe("a question that takes several answers", () => {
    it("offers them as boxes to tick rather than a single pick", async () => {
      const onAnswered = renderChoice({ maxAnswers: 0 });

      expect(screen.queryByRole("radio")).not.toBeInTheDocument();
      await userEvent.click(screen.getByRole("checkbox", { name: "Full day" }));

      expect(onAnswered).toHaveBeenCalledWith([ "full-day" ]);
    });

    // Two people answering the same way should store the same thing, whatever order they clicked in
    it("keeps the answers in the order they are offered, not the order they were picked", async () => {
      const onAnswered = renderChoice({ maxAnswers: 0 }, [ "multiple-days" ]);

      await userEvent.click(screen.getByRole("checkbox", { name: "Half day" }));

      expect(onAnswered).toHaveBeenCalledWith([ "half-day", "multiple-days" ]);
    });

    it("takes an answer back when its box is unticked", async () => {
      const onAnswered = renderChoice({ maxAnswers: 0 }, [ "half-day", "full-day" ]);

      await userEvent.click(screen.getByRole("checkbox", { name: "Half day" }));

      expect(onAnswered).toHaveBeenCalledWith([ "full-day" ]);
    });

    it("describes the question when the schema explains it", () => {
      renderChoice({ maxAnswers: 0, description: "As many as apply." });

      expect(screen.getByText("As many as apply.")).toBeInTheDocument();
    });

    // The rule is shown, not merely enforced: at the cap the remaining boxes grey out instead of
    // letting a pick be made only to be refused by the save
    it("stops offering once as many as the question takes are picked", () => {
      renderChoice({ maxAnswers: 2 }, [ "half-day", "full-day" ]);

      expect(screen.getByRole("checkbox", { name: "Several days" })).toBeDisabled();
      // The picked ones stay live, so the choice can still be changed
      expect(screen.getByRole("checkbox", { name: "Half day" })).toBeEnabled();
    });

    it("keeps offering while the cap is not reached", () => {
      renderChoice({ maxAnswers: 2 }, [ "half-day" ]);

      expect(screen.getByRole("checkbox", { name: "Several days" })).toBeEnabled();
    });

    it("says how many to choose", () => {
      renderChoice({ minAnswers: 2, maxAnswers: 3, description: "Your days." });

      expect(screen.getByText("Your days. Choose at least 2. Choose up to 3.")).toBeInTheDocument();
    });
  });
});
