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

import BooleanAnswer from "@iap/submissions/answers/BooleanAnswer";
import { QUESTION, type FormQuestion } from "@iap/submissions/submissionForm";

function renderBoolean(overrides: Partial<FormQuestion> = {}, values: string[] = []) {
  const question: FormQuestion = {
    name: "recurring", type: QUESTION, path: "details/recurring", text: "Does this repeat?",
    dataType: "boolean", minAnswers: 0, maxAnswers: 1, options: [], value: [], ...overrides,
  };
  const onAnswered = vi.fn();
  render(<BooleanAnswer question={question} values={values} disabled={false}
    onChange={vi.fn()} onAnswered={onAnswered} />);
  return onAnswered;
}

describe("BooleanAnswer", () => {
  // A tick is a finished answer the moment it happens: there is no field to leave, so waiting for a
  // blur the way the typed inputs do would lose it
  it("saves as the box is ticked, without waiting to be left", async () => {
    const onAnswered = renderBoolean();

    await userEvent.click(screen.getByRole("checkbox", { name: /Does this repeat/ }));

    expect(onAnswered).toHaveBeenCalledWith([ "true" ]);
  });

  it("saves the untick too", async () => {
    const onAnswered = renderBoolean({}, [ "true" ]);

    await userEvent.click(screen.getByRole("checkbox", { name: /Does this repeat/ }));

    expect(onAnswered).toHaveBeenCalledWith([ "false" ]);
  });

  it("shows the answer already given", () => {
    renderBoolean({}, [ "true" ]);

    expect(screen.getByRole("checkbox", { name: /Does this repeat/ })).toBeChecked();
  });

  it("explains the question when the schema does", () => {
    renderBoolean({ description: "Weekly, monthly, or not at all." });

    expect(screen.getByText("Weekly, monthly, or not at all.")).toBeInTheDocument();
  });
});
