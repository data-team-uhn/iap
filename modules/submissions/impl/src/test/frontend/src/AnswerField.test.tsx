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

import AnswerField from "@iap/submissions/AnswerField";
import { QUESTION, type FormQuestion } from "@iap/submissions/submissionForm";

function question(overrides: Partial<FormQuestion> = {}): FormQuestion {
  return {
    name: "startDate",
    type: QUESTION,
    path: "details/startDate",
    text: "Which day does your time off start?",
    dataType: "date",
    required: false,
    multiple: false,
    options: [],
    value: [],
    ...overrides,
  };
}

describe("AnswerField", () => {
  it("saves a typed answer when the field is left, not while typing", async () => {
    // The whole design rests on this: saving each answer as it is finished is what keeps the stored
    // answers current enough for the server to re-decide which questions apply, without a request
    // per keystroke
    const answered = vi.fn();
    render(<AnswerField question={question({ dataType: "text" })} state="idle" onAnswered={answered} />);

    const field = screen.getByLabelText(/Which day/);
    await userEvent.type(field, "multiple days");
    expect(answered).not.toHaveBeenCalled();

    await userEvent.tab();
    expect(answered).toHaveBeenCalledWith([ "multiple days" ]);
  });

  it("does not save an answer that did not change", async () => {
    // Leaving a field one did not edit would otherwise be a workflow event that means nothing
    const answered = vi.fn();
    render(<AnswerField question={question({ value: [ "2026-10-06" ] })} state="idle" onAnswered={answered} />);

    await userEvent.click(screen.getByLabelText(/Which day/));
    await userEvent.tab();

    expect(answered).not.toHaveBeenCalled();
  });

  it("saves a tick as soon as it happens", async () => {
    // There is nothing to leave: the answer is complete the moment the box changes
    const answered = vi.fn();
    render(<AnswerField question={question({ dataType: "boolean" })} state="idle" onAnswered={answered} />);

    await userEvent.click(screen.getByRole("checkbox"));

    expect(answered).toHaveBeenCalledWith([ "true" ]);
  });

  it("types each declared data type in its own way", () => {
    const { rerender } = render(<AnswerField question={question()} state="idle" onAnswered={vi.fn()} />);
    expect(screen.getByLabelText(/Which day/)).toHaveAttribute("type", "date");

    rerender(<AnswerField question={question({ dataType: "long" })} state="idle" onAnswered={vi.fn()} />);
    expect(screen.getByLabelText(/Which day/)).toHaveAttribute("type", "number");

    rerender(<AnswerField question={question({ dataType: "text" })} state="idle" onAnswered={vi.fn()} />);
    expect(screen.getByLabelText(/Which day/)).toHaveAttribute("type", "text");
  });

  // Deliberately not a text box: typing into an input never meant for this question stores a value
  // the schema does not accept, and nothing notices until a condition somewhere stops matching
  it("says so when a question asks for something it has no way to answer", () => {
    render(<AnswerField question={question({ dataType: "invented" })} state="idle" onAnswered={vi.fn()} />);

    expect(screen.getByText(/asks for invented, which cannot be answered here/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/Which day/)).not.toBeInTheDocument();
  });

  // Offering answers says more about a question than its data type does, so the choice component
  // outbids the one that would otherwise have typed it in
  it("offers the answers a question declares instead of a box to type in", async () => {
    const answered = vi.fn();
    render(<AnswerField
      question={question({
        dataType: "text",
        options: [ { value: "half-day", label: "Half day" }, { value: "multiple-days", label: "Several days" } ],
      })}
      state="idle"
      onAnswered={answered}
    />);

    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.getByRole("radiogroup", { name: /Which day/ })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("radio", { name: "Several days" }));

    // The option's value, not the label the submitter read
    expect(answered).toHaveBeenCalledWith([ "multiple-days" ]);
  });

  it("takes several values one per line, dropping the blank ones", async () => {
    const answered = vi.fn();
    render(<AnswerField question={question({ dataType: "text", multiple: true })} state="idle" onAnswered={answered} />);

    await userEvent.type(screen.getByLabelText(/Which day/), "Monday\n\nTuesday");
    await userEvent.tab();

    expect(answered).toHaveBeenCalledWith([ "Monday", "Tuesday" ]);
  });

  it("says a file cannot be attached here yet, rather than pretending otherwise", () => {
    // A text box would quietly store a filename, which is worse than saying so
    render(<AnswerField question={question({ dataType: "file" })} state="idle" onAnswered={vi.fn()} />);

    expect(screen.getByText(/not available yet/)).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("shows what the save is doing, including that it failed", () => {
    const { rerender } = render(
      <AnswerField question={question()} state="saving" onAnswered={vi.fn()} />);
    expect(screen.getByLabelText("Saving")).toBeInTheDocument();

    rerender(<AnswerField question={question()} state="saved" onAnswered={vi.fn()} />);
    expect(screen.getByText("Saved")).toBeInTheDocument();

    // Where a save can be refused, so where it has to be reported: a field that looked saved and was
    // not would be a lie
    rerender(<AnswerField question={question()} state="failed" error="No longer a draft" onAnswered={vi.fn()} />);
    expect(screen.getByLabelText("Not saved")).toBeInTheDocument();
  });

  it("adopts a new stored answer, and keeps what is being typed when nothing changed", async () => {
    // Each read of the form returns fresh arrays, so following the prop by identity would reset every
    // field on every save — including one somebody is halfway through typing in, because a *different*
    // field was saved. It follows the content instead.
    const initial = question({ dataType: "text" });
    const { rerender } = render(<AnswerField question={initial} state="idle" onAnswered={vi.fn()} />);
    await userEvent.type(screen.getByLabelText(/Which day/), "half day");

    rerender(<AnswerField question={question({ dataType: "text", value: [] })} state="saved" onAnswered={vi.fn()} />);
    expect(screen.getByLabelText(/Which day/)).toHaveValue("half day");

    rerender(
      <AnswerField question={question({ dataType: "text", value: [ "full day" ] })} state="saved" onAnswered={vi.fn()} />);
    expect(screen.getByLabelText(/Which day/)).toHaveValue("full day");
  });

  it("tells a regrouped multi-value answer from the same text stored as one value", () => {
    // The values are compared as one joined string, so the separator has to be a character an answer
    // cannot contain. Joining on a space would make [ "Monday", "Tuesday" ] and [ "Monday Tuesday" ]
    // equal, and a re-read that regrouped them would leave the field showing the grouping it replaced.
    const many = { dataType: "text", multiple: true };
    const { rerender } = render(
      <AnswerField question={question({ ...many, value: [ "Monday", "Tuesday" ] })} state="saved" onAnswered={vi.fn()} />);
    expect(screen.getByLabelText(/Which day/)).toHaveValue("Monday\nTuesday");

    rerender(
      <AnswerField question={question({ ...many, value: [ "Monday Tuesday" ] })} state="saved" onAnswered={vi.fn()} />);
    expect(screen.getByLabelText(/Which day/)).toHaveValue("Monday Tuesday");
  });

  it("cannot be answered when the request may no longer be changed", () => {
    render(<AnswerField question={question()} state="idle" disabled onAnswered={vi.fn()} />);

    expect(screen.getByLabelText(/Which day/)).toBeDisabled();
  });

  it("falls back to the question's name when it has no text", () => {
    render(<AnswerField question={question({ text: "" })} state="idle" onAnswered={vi.fn()} />);

    expect(screen.getByLabelText("startDate")).toBeInTheDocument();
  });
});
