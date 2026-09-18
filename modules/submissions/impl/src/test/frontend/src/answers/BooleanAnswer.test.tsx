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
    dataType: "boolean", required: false, multiple: false, options: [], value: [], ...overrides,
  };
  const onAnswered = vi.fn();
  render(<BooleanAnswer question={question} values={values} disabled={false}
    onChange={vi.fn()} onAnswered={onAnswered} />);
  return onAnswered;
}

describe("BooleanAnswer", () => {
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

  // A required boolean is two radios, so that No is an answer rather than the absence of one
  it("offers a required question as two options, neither chosen until one is given", () => {
    renderBoolean({ required: true });

    expect(screen.getByRole("radio", { name: "Yes" })).not.toBeChecked();
    expect(screen.getByRole("radio", { name: "No" })).not.toBeChecked();
  });

  it("records a required No as an answer", async () => {
    const onAnswered = renderBoolean({ required: true });

    await userEvent.click(screen.getByRole("radio", { name: "No" }));

    expect(onAnswered).toHaveBeenCalledWith([ "false" ]);
  });

  it("shows which of the two a required question already holds", () => {
    renderBoolean({ required: true }, [ "false" ]);

    expect(screen.getByRole("radio", { name: "No" })).toBeChecked();
  });

  // Each input carries it: a FormControl never forwards required to a radio
  it("marks a required question's options required", () => {
    renderBoolean({ required: true });

    expect(screen.getByRole("radio", { name: "Yes" })).toBeRequired();
    expect(screen.getByRole("radio", { name: "No" })).toBeRequired();
  });

  it("explains a required question when the schema does", () => {
    renderBoolean({ required: true, description: "Weekly, monthly, or not at all." });

    expect(screen.getByText("Weekly, monthly, or not at all.")).toBeInTheDocument();
  });

  it("explains the question when the schema does", () => {
    renderBoolean({ description: "Weekly, monthly, or not at all." });

    expect(screen.getByText("Weekly, monthly, or not at all.")).toBeInTheDocument();
  });
});
