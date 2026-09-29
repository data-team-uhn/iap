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
  it("saves as soon as one is picked, without waiting to be left", async () => {
    const onAnswered = renderBoolean();

    await userEvent.click(screen.getByRole("radio", { name: "Yes" }));

    expect(onAnswered).toHaveBeenCalledWith([ "true" ]);
  });

  it("records No as an answer in its own right", async () => {
    const onAnswered = renderBoolean();

    await userEvent.click(screen.getByRole("radio", { name: "No" }));

    expect(onAnswered).toHaveBeenCalledWith([ "false" ]);
  });

  it("shows the answer already given", () => {
    renderBoolean({}, [ "false" ]);

    expect(screen.getByRole("radio", { name: "No" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "Yes" })).not.toBeChecked();
  });

  // Why this is a pair rather than one box to tick: an unticked box says "no" and "not answered
  // yet" with the same control, and only one of those is an answer
  it("holds neither answer until one is given", () => {
    renderBoolean();

    expect(screen.getByRole("radio", { name: "Yes" })).not.toBeChecked();
    expect(screen.getByRole("radio", { name: "No" })).not.toBeChecked();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  });

  // Whether an answer is obligatory changes what the form insists on, never what it looks like:
  // a submitter should not have to learn a second control to answer the same kind of question
  it("asks the same way whether or not the answer is obligatory", () => {
    renderBoolean({ required: true });

    expect(screen.getByRole("radio", { name: "Yes" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "No" })).toBeInTheDocument();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  });

  // Each input carries it: a FormControl never forwards required to a radio
  it("marks a required question's options required", () => {
    renderBoolean({ required: true });

    expect(screen.getByRole("radio", { name: "Yes" })).toBeRequired();
    expect(screen.getByRole("radio", { name: "No" })).toBeRequired();
  });

  it("leaves an optional question's options not required", () => {
    renderBoolean();

    expect(screen.getByRole("radio", { name: "Yes" })).not.toBeRequired();
    expect(screen.getByRole("radio", { name: "No" })).not.toBeRequired();
  });

  it("asks the question the schema asks", () => {
    renderBoolean();

    expect(screen.getByRole("group", { name: /Does this repeat/ })).toBeInTheDocument();
  });

  it("explains the question when the schema does", () => {
    renderBoolean({ description: "Weekly, monthly, or not at all." });

    expect(screen.getByText("Weekly, monthly, or not at all.")).toBeInTheDocument();
  });
});
