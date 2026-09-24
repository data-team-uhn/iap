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

import AnswerProvenance, { ConfirmAnswer } from "@iap/submissions/AnswerProvenance";
import type { QuestionProvenance } from "@iap/submissions/provenance";

// The PDF of the document the answers were read from, as the projection serves it
const PDF = "/Submissions/demo-1/proposal/1/file/file.pdf";

function provenance(overrides: Partial<QuestionProvenance> = {}): QuestionProvenance {
  return {
    suggested: [ "Yes" ],
    confidence: 0.9,
    passages: [ {
      quote: "Participants receive pembrolizumab 200 mg IV every three weeks, supplied by the sponsor.",
      cite: "p. 9 · §5.1 Study treatment",
      source: `${PDF}#page=9`,
    } ],
    reviewed: false,
    evidenceRejected: false,
    ...overrides,
  };
}

function show(overrides: Partial<QuestionProvenance> = {}, value = [ "Yes" ]) {
  const accept = vi.fn();
  const reject = vi.fn();
  // The two as the field places them: the button beside the answer, the evidence in its own column
  render(
    <>
      <ConfirmAnswer provenance={provenance(overrides)} value={value} onAccept={accept} />
      <AnswerProvenance
        provenance={provenance(overrides)}
        value={value}
        onAccept={accept}
        onRejectEvidence={reject}
      />
    </>,
  );
  return { accept, reject };
}

describe("AnswerProvenance", () => {
  it("says where the answer came from and quotes the passage it cited", async () => {
    show();

    expect(screen.getByText("AI found:")).toBeInTheDocument();
    // Where the passage sits is shown with the passage itself, once it is opened
    await userEvent.click(screen.getByRole("button", { name: /Participants receive pembrolizumab/ }));
    expect(screen.getByText("p. 9 · §5.1 Study treatment")).toBeInTheDocument();
  });

  // Nothing opens itself: the excerpt in the trigger is usually enough to judge, so seeing the whole
  // passage is always a deliberate reveal
  it("keeps the passage closed until it is asked for", async () => {
    show();
    expect(screen.queryByText(/supplied by the sponsor/)).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /pembrolizumab/ }));

    expect(screen.getByText(/supplied by the sponsor/)).toBeInTheDocument();
  });

  it("settles the answer when the submitter says it looks right", async () => {
    const { accept } = show();

    await userEvent.click(screen.getByRole("button", { name: "Confirm answer" }));

    expect(accept).toHaveBeenCalled();
  });

  it("offers nothing to accept once the answer is settled", () => {
    show({ reviewed: true });

    expect(screen.queryByRole("button", { name: "Confirm answer" })).not.toBeInTheDocument();
    expect(screen.getByText("Matches your protocol")).toBeInTheDocument();
  });

  it("says so when the submitter corrected the answer", () => {
    show({ reviewed: true }, [ "No" ]);

    expect(screen.getByText("You corrected this")).toBeInTheDocument();
  });

  it("offers nothing to accept when the form is read-only", () => {
    render(<ConfirmAnswer provenance={provenance()} value={[ "Yes" ]} disabled onAccept={vi.fn()} />);

    expect(screen.queryByRole("button", { name: "Confirm answer" })).not.toBeInTheDocument();
  });

  it("flags a doubtful suggestion and says whose answer counts", async () => {
    show({ confidence: 0.4 });

    expect(screen.getByText("Low confidence")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /pembrolizumab/ }));
    expect(screen.getByText(/your answer is what counts/)).toBeInTheDocument();
  });

  it("says nothing about confidence when the extraction was sure", () => {
    show();

    expect(screen.queryByText("Low confidence")).not.toBeInTheDocument();
  });

  // Flagging the passage is telemetry about the extraction, not a step in the submitter's job, so
  // it settles nothing and the answer keeps its own state
  it("records a passage that does not support the answer, without settling anything", async () => {
    const { reject } = show();
    await userEvent.click(screen.getByRole("button", { name: /pembrolizumab/ }));

    await userEvent.click(screen.getByRole("button", { name: /doesn.t support the answer/ }));

    expect(reject).toHaveBeenCalledWith(true);
    expect(screen.getByText("AI found:")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Confirm answer" })).toBeInTheDocument();
  });

  it("lets the submitter take that back", async () => {
    const { reject } = show({ evidenceRejected: true });
    await userEvent.click(screen.getByRole("button", { name: /pembrolizumab/ }));

    await userEvent.click(screen.getByRole("button", { name: "Undo" }));

    expect(reject).toHaveBeenCalledWith(false);
  });

  // Some sources carry no usable structure, so the link must not promise a spot it cannot reach
  it("offers only to search when the passage names no place", async () => {
    show({ passages: [ { quote: "Symptom diaries may be completed electronically.", source: PDF } ] });

    await userEvent.click(screen.getByRole("button", { name: /Symptom diaries/ }));

    expect(screen.getByRole("link", { name: "Find in protocol" })).toHaveAttribute("href", PDF);
  });

  // The link goes to the document itself, at the page the quote sits on
  it("opens the document at the page the passage came from", async () => {
    show();

    await userEvent.click(screen.getByRole("button", { name: /pembrolizumab/ }));

    expect(screen.getByRole("link", { name: "Open in protocol" }))
      .toHaveAttribute("href", `${PDF}#page=9`);
  });

  // A parse that produced no PDF leaves nothing to open, and a link that led nowhere would be worse
  // than none: it reads as though the document were one click away
  it("offers no link when the passage has no document behind it", async () => {
    show({ passages: [ { quote: "Symptom diaries may be completed electronically." } ] });

    await userEvent.click(screen.getByRole("button", { name: /Symptom diaries/ }));

    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("shows nothing to open when the extraction offered no passage", () => {
    show({ passages: [] });

    expect(screen.getByText("AI found:")).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  // The server verifies every passage against the document and sends the ones that held up, so
  // showing one of three understated the evidence and left the other two where nobody could see them
  it("shows every passage the extraction offered, not just the first", async () => {
    show({ passages: [
      { quote: "The first passage.", cite: "p. 3", source: PDF },
      { quote: "The second passage.", cite: "p. 7", source: PDF },
    ] });

    expect(screen.getByText("+1 more passage")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /The first passage/ }));

    // Twice for the first: once on the collapsed line that opened this, once as the passage itself
    expect(screen.getAllByText(/The first passage/)).toHaveLength(2);
    expect(screen.getByText(/The second passage/)).toBeInTheDocument();
    expect(screen.getByText("p. 7")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "Open in protocol" })).toHaveLength(2);
  });

  it("says nothing about further passages when there is only the one", () => {
    show();

    expect(screen.queryByText(/more passage/)).not.toBeInTheDocument();
  });
});
