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
    expect(screen.getByLabelText(/Which day/).tagName).toBe("TEXTAREA");
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

describe("AnswerField with a suggestion", () => {
  function suggested(overrides: Partial<FormQuestion> = {}): FormQuestion {
    return question({
      name: "sponsor", path: "details/sponsor", text: "Who sponsors the study?", dataType: "text",
      provenance: {
        suggested: [ "Acme Pharma" ],
        offered: [ "Acme Pharma" ],
        confidence: 0.9,
        passages: [ { quote: "The study is sponsored by Acme Pharma" } ],
        evidenceRejected: false,
        actedOn: false,
      },
      ...overrides,
    });
  }

  it("shows the suggestion in an empty field, and saves it only once confirmed", async () => {
    const answered = vi.fn();
    render(<AnswerField question={suggested()} state="idle" onAnswered={answered} />);

    const field = screen.getByLabelText(/Who sponsors/);
    expect(field).toHaveValue("Acme Pharma");
    expect(screen.getByText("AI found:")).toBeInTheDocument();
    // Leaving it as it is takes nothing
    await userEvent.click(field);
    await userEvent.tab();
    expect(answered).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole("button", { name: "Confirm answer" }));
    expect(answered).toHaveBeenCalledWith([ "Acme Pharma" ]);
  });

  it("saves an edit of the suggestion as the answer, and drops the Confirm button with the first keystroke", async () => {
    const answered = vi.fn();
    render(<AnswerField question={suggested()} state="idle" onAnswered={answered} />);

    const field = screen.getByLabelText(/Who sponsors/);
    await userEvent.type(field, " Inc");
    expect(screen.queryByRole("button", { name: "Confirm answer" })).not.toBeInTheDocument();
    await userEvent.tab();

    expect(answered).toHaveBeenCalledWith([ "Acme Pharma Inc" ]);
  });

  it("saves nothing when an empty field shows nothing and is left empty", async () => {
    const answered = vi.fn();
    render(<AnswerField question={suggested({ provenance: undefined })} state="idle" onAnswered={answered} />);

    await userEvent.click(screen.getByLabelText(/Who sponsors/));
    await userEvent.tab();

    expect(answered).not.toHaveBeenCalled();
  });

  it("keeps the submitter's answer and offers what a newer document says", async () => {
    const answered = vi.fn();
    const question = suggested({ value: [ "Beta Labs" ] });
    render(<AnswerField question={question} state="idle" onAnswered={answered} />);

    expect(screen.getByLabelText(/Who sponsors/)).toHaveValue("Beta Labs");
    expect(screen.getByText("You answered differently")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "What the newer document says" }));
    expect(screen.getByText("Acme Pharma")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Close" }));
    await userEvent.click(screen.getByRole("button", { name: "What the newer document says" }));
    await userEvent.click(screen.getByRole("button", { name: "Use this answer" }));

    expect(answered).toHaveBeenCalledWith([ "Acme Pharma" ]);
  });

  it("frames the picked option of a suggested choice", () => {
    const question = suggested({
      dataType: "boolean", text: "Is a drug given?",
      provenance: {
        suggested: [ "true" ], offered: [ "true" ], confidence: 0.9, passages: [],
        evidenceRejected: false, actedOn: false,
      },
    });
    render(<AnswerField question={question} state="idle" onAnswered={vi.fn()} />);

    expect(screen.getByRole("radio", { name: "Yes" })).toBeChecked();
  });

  it("frames the picked option of a suggested pick from a list", () => {
    const question = suggested({
      text: "Which phase?",
      options: [ { value: "one", label: "Phase 1" }, { value: "two", label: "Phase 2" } ],
      provenance: {
        suggested: [ "two" ], offered: [ "two" ], confidence: 0.9, passages: [],
        evidenceRejected: false, actedOn: false,
      },
    });
    render(<AnswerField question={question} state="idle" onAnswered={vi.fn()} />);

    expect(screen.getByRole("radio", { name: "Phase 2" })).toBeChecked();
  });

  it("frames the ticked boxes of a suggested list", () => {
    const question = suggested({
      text: "Which sites?", maxAnswers: 0,
      options: [ { value: "a", label: "Site A" }, { value: "b", label: "Site B" } ],
      provenance: {
        suggested: [ "b" ], offered: [ "b" ], confidence: 0.9, passages: [],
        evidenceRejected: false, actedOn: false,
      },
    });
    render(<AnswerField question={question} state="idle" onAnswered={vi.fn()} />);

    expect(screen.getByRole("checkbox", { name: "Site B" })).toBeChecked();
  });

  it("frames a suggested number and date", () => {
    const number = suggested({
      dataType: "long", text: "How many participants?",
      provenance: {
        suggested: [ "120" ], offered: [ "120" ], confidence: 0.9, passages: [],
        evidenceRejected: false, actedOn: false,
      },
    });
    const { unmount } = render(<AnswerField question={number} state="idle" onAnswered={vi.fn()} />);
    expect(screen.getByLabelText(/How many participants/)).toHaveValue(120);
    unmount();

    const date = suggested({
      dataType: "date", text: "When does it start?",
      provenance: {
        suggested: [ "2027-01-04" ], offered: [ "2027-01-04" ], confidence: 0.9, passages: [],
        evidenceRejected: false, actedOn: false,
      },
    });
    render(<AnswerField question={date} state="idle" onAnswered={vi.fn()} />);
    expect(screen.getByLabelText(/When does it start/)).toHaveValue("2027-01-04");
  });

  it("passes on a note that the quotes do not support the suggestion", async () => {
    const rejected = vi.fn();
    render(<AnswerField question={suggested()} state="idle" onAnswered={vi.fn()} onRejectEvidence={rejected} />);

    await userEvent.click(screen.getByRole("button", { name: /The study is sponsored/ }));
    await userEvent.click(screen.getByRole("button", { name: "This passage does not support the answer" }));

    expect(rejected).toHaveBeenCalledWith(true);
  });

  it("takes a note quietly when nobody listens for it", async () => {
    render(<AnswerField question={suggested()} state="idle" onAnswered={vi.fn()} />);

    await userEvent.click(screen.getByRole("button", { name: /The study is sponsored/ }));
    await userEvent.click(screen.getByRole("button", { name: "This passage does not support the answer" }));

    expect(screen.getByText("AI found:")).toBeInTheDocument();
  });
});
