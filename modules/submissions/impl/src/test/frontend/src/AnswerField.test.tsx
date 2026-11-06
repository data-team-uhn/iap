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

import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import AnswerField from "@iap/submissions/AnswerField";
import { loadAnswerComponents } from "@iap/submissions/answers";
// Imported rather than fetched. In a browser each answer component arrives as its own asset, named
// by an extension on the AnswerComponent point, and registers itself as it is evaluated.
import "@iap/submissions/answers/BooleanAnswer";
import "@iap/submissions/answers/ChoiceAnswer";
import "@iap/submissions/answers/DateAnswer";
import "@iap/submissions/answers/NumberAnswer";
import "@iap/submissions/answers/TextAnswer";
import { QUESTION, type FormQuestion } from "@iap/submissions/submissionForm";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(() => Promise.resolve([])),
}));

// Settles the load before the first render, so a field draws its input rather than the spinner it
// shows while the components are still on their way
beforeEach(async () => {
  await loadAnswerComponents();
});

function question(overrides: Partial<FormQuestion> = {}): FormQuestion {
  return {
    name: "startDate",
    type: QUESTION,
    path: "details/startDate",
    text: "Which day does your time off start?",
    dataType: "date",
    minAnswers: 0,
    maxAnswers: 1,
    options: [],
    value: [],
    ...overrides,
  };
}

describe("AnswerField", () => {
  it("saves a typed answer when the field is left, not while typing", async () => {
    const answered = vi.fn();
    render(<AnswerField question={question({ dataType: "text" })} state="idle" onAnswered={answered} />);

    const field = screen.getByLabelText(/Which day/);
    await userEvent.type(field, "multiple days");
    expect(answered).not.toHaveBeenCalled();

    await userEvent.tab();
    expect(answered).toHaveBeenCalledWith([ "multiple days" ]);
  });

  it("does not save an answer that did not change", async () => {
    const answered = vi.fn();
    render(<AnswerField question={question({ value: [ "2026-10-06" ] })} state="idle" onAnswered={answered} />);

    await userEvent.click(screen.getByLabelText(/Which day/));
    await userEvent.tab();

    expect(answered).not.toHaveBeenCalled();
  });

  it("saves a pick as soon as it happens", async () => {
    const answered = vi.fn();
    render(<AnswerField question={question({ dataType: "boolean" })} state="idle" onAnswered={answered} />);

    await userEvent.click(screen.getByRole("radio", { name: "Yes" }));

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

  it("says so when a question asks for something it has no way to answer", () => {
    render(<AnswerField question={question({ dataType: "invented" })} state="idle" onAnswered={vi.fn()} />);

    expect(screen.getByText(/asks for invented, which cannot be answered here/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/Which day/)).not.toBeInTheDocument();
  });

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
    render(<AnswerField question={question({ dataType: "text", maxAnswers: 0 })} state="idle" onAnswered={answered} />);

    await userEvent.type(screen.getByLabelText(/Which day/), "Monday\n\nTuesday");
    await userEvent.tab();

    expect(answered).toHaveBeenCalledWith([ "Monday", "Tuesday" ]);
  });

  it("shows what the save is doing, including that it failed", () => {
    const { rerender } = render(
      <AnswerField question={question()} state="saving" onAnswered={vi.fn()} />);
    expect(screen.getByLabelText("Saving")).toBeInTheDocument();

    rerender(<AnswerField question={question()} state="saved" onAnswered={vi.fn()} />);
    expect(screen.getByText("Saved")).toBeInTheDocument();

    rerender(<AnswerField question={question()} state="failed" error="No longer a draft" onAnswered={vi.fn()} />);
    expect(screen.getByText("Not saved")).toBeInTheDocument();
  });

  it("says a save failed even when nothing said why", async () => {
    render(<AnswerField question={question()} state="failed" onAnswered={vi.fn()} />);

    fireEvent.mouseOver(screen.getByText("Not saved"));

    expect(await screen.findByRole("tooltip")).toHaveTextContent("This answer was not saved");
  });

  it("adopts a new stored answer, and keeps what is being typed when nothing changed", async () => {
    const initial = question({ dataType: "text" });
    const { rerender } = render(<AnswerField question={initial} state="idle" onAnswered={vi.fn()} />);
    await userEvent.type(screen.getByLabelText(/Which day/), "half day");

    // A fresh array holding the same answer
    rerender(<AnswerField question={question({ dataType: "text", value: [] })} state="saved" onAnswered={vi.fn()} />);
    expect(screen.getByLabelText(/Which day/)).toHaveValue("half day");

    // A new answer, while the field still has focus
    rerender(
      <AnswerField question={question({ dataType: "text", value: [ "full day" ] })} state="saved" onAnswered={vi.fn()} />);
    expect(screen.getByLabelText(/Which day/)).toHaveValue("half day");

    // Adopted as soon as they leave it
    await userEvent.tab();
    rerender(
      <AnswerField question={question({ dataType: "text", value: [ "full day" ] })} state="saved" onAnswered={vi.fn()} />);
    expect(screen.getByLabelText(/Which day/)).toHaveValue("full day");
  });

  it("tells a regrouped multi-value answer from the same text stored as one value", () => {
    // Joined on a space, the two answers would compare equal, and the field would keep showing the
    // first grouping
    const many = { dataType: "text", maxAnswers: 0 };
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
