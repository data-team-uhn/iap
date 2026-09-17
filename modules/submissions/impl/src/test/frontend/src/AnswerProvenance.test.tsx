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

import AnswerProvenance from "@iap/submissions/AnswerProvenance";
import type { QuestionProvenance } from "@iap/submissions/provenance";

function provenance(overrides: Partial<QuestionProvenance> = {}): QuestionProvenance {
  return {
    suggested: [ "Yes" ],
    confidence: 0.9,
    passages: [ {
      quote: "Participants receive pembrolizumab 200 mg IV every three weeks, supplied by the sponsor.",
      span: "pembrolizumab 200 mg IV every three weeks",
      cite: "p. 9 · §5.1 Study treatment",
    } ],
    reviewed: false,
    evidenceRejected: false,
    ...overrides,
  };
}

function show(overrides: Partial<QuestionProvenance> = {}, value = [ "Yes" ]) {
  const accept = vi.fn();
  const reject = vi.fn();
  render(
    <AnswerProvenance
      provenance={provenance(overrides)}
      value={value}
      onAccept={accept}
      onRejectEvidence={reject}
    />,
  );
  return { accept, reject };
}

describe("AnswerProvenance", () => {
  it("says where the answer came from and quotes what it keyed on", () => {
    show();

    expect(screen.getByText("AI found:")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /pembrolizumab 200 mg IV every three weeks/ }))
      .toBeInTheDocument();
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

    await userEvent.click(screen.getByRole("button", { name: "Looks right" }));

    expect(accept).toHaveBeenCalled();
  });

  it("offers nothing to accept once the answer is settled", () => {
    show({ reviewed: true });

    expect(screen.queryByRole("button", { name: "Looks right" })).not.toBeInTheDocument();
    expect(screen.getByText("Matches your protocol")).toBeInTheDocument();
  });

  it("says so when the submitter corrected the answer", () => {
    show({ reviewed: true }, [ "No" ]);

    expect(screen.getByText("You corrected this")).toBeInTheDocument();
  });

  it("offers nothing to accept when the form is read-only", () => {
    render(
      <AnswerProvenance
        provenance={provenance()}
        value={[ "Yes" ]}
        disabled
        onAccept={vi.fn()}
        onRejectEvidence={vi.fn()}
      />,
    );

    expect(screen.queryByRole("button", { name: "Looks right" })).not.toBeInTheDocument();
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
    expect(screen.getByRole("button", { name: "Looks right" })).toBeInTheDocument();
  });

  it("lets the submitter take that back", async () => {
    const { reject } = show({ evidenceRejected: true });
    await userEvent.click(screen.getByRole("button", { name: /pembrolizumab/ }));

    await userEvent.click(screen.getByRole("button", { name: "Undo" }));

    expect(reject).toHaveBeenCalledWith(false);
  });

  // Some sources carry no usable structure, so the link must not promise a spot it cannot reach
  it("offers only to search when the passage names no place", async () => {
    show({ passages: [ { quote: "Symptom diaries may be completed electronically." } ] });

    await userEvent.click(screen.getByRole("button", { name: /Symptom diaries/ }));

    expect(screen.getByRole("link", { name: "Find in protocol" })).toBeInTheDocument();
  });

  it("shows nothing to open when the extraction offered no passage", () => {
    show({ passages: [] });

    expect(screen.getByText("AI found:")).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });
});
