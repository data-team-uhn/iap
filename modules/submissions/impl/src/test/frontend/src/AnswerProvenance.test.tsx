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

import type { PdfViewerProps } from "@iap/frontend-commons/pdfViewer/PdfViewer";
import AnswerProvenance, { ConfirmAnswer } from "@iap/submissions/AnswerProvenance";
import type { ProvenanceStatus, QuestionProvenance } from "@iap/submissions/provenance";

// The shared viewer has tests of its own, so here it only shows what it was asked to open
const opened = vi.hoisted((): { props?: PdfViewerProps } => ({}));
vi.mock("@iap/frontend-commons/pdfViewer/PdfViewer", () => ({
  default: (props: PdfViewerProps) => {
    opened.props = props;
    return (
      <div role="dialog">
        <h2>{props.title}</h2>
        <button type="button" onClick={props.onClose}>Close the document</button>
      </div>
    );
  },
}));

const PDF = "/Submissions/demo-1/proposal/v1/file/pdfFile";

function provenance(overrides: Partial<QuestionProvenance> = {}): QuestionProvenance {
  return {
    suggested: [ "drug" ],
    offered: [ "drug" ],
    confidence: 0.9,
    passages: [ {
      quote: "Participants receive pembrolizumab 200 mg IV every three weeks, supplied by the sponsor.",
      cite: "p. 9 · Study treatment",
      source: `${PDF}#page=9`,
    } ],
    evidenceRejected: false,
    actedOn: false,
    ...overrides,
  };
}

function show(overrides: Partial<QuestionProvenance> = {}, status: ProvenanceStatus = "suggested",
  disabled = false) {
  const reject = vi.fn();
  render(<AnswerProvenance provenance={provenance(overrides)} status={status} disabled={disabled}
    onRejectEvidence={reject} />);
  return reject;
}

describe("AnswerProvenance", () => {
  beforeEach(() => {
    opened.props = undefined;
  });

  it("says where the answer stands and quotes the opening of its first passage", () => {
    show();

    expect(screen.getByText("AI found:")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Participants receive pembrolizumab/ }))
      .toHaveAttribute("aria-expanded", "false");
  });

  it("names a settled answer and counts the passages it does not quote", () => {
    show({ passages: [ { quote: "one" }, { quote: "two" } ] }, "accepted");

    expect(screen.getByText("Matches your document")).toBeInTheDocument();
    expect(screen.getByText("+1 more passage")).toBeInTheDocument();
  });

  it("shows no quote line for a suggestion with no passage", () => {
    show({ passages: [] }, "changed");

    expect(screen.getByText("You answered differently")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("opens every passage with the reasoning, and the document at the quote", async () => {
    show({ reasoning: "The protocol names the drug" });

    await userEvent.click(screen.getByRole("button", { name: /Participants receive/ }));
    expect(screen.getByText("p. 9 · Study treatment")).toBeInTheDocument();
    expect(screen.getByText("The protocol names the drug")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Open in document" }));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(opened.props?.url).toBe(PDF);
    expect(opened.props?.page).toBe(9);

    await userEvent.click(screen.getByRole("button", { name: "Close the document" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("offers no document for a passage without one", async () => {
    show({ passages: [ { quote: "Somewhere in the text" } ] });

    await userEvent.click(screen.getByRole("button", { name: /Somewhere in the text/ }));

    expect(screen.queryByRole("button", { name: /in document/ })).not.toBeInTheDocument();
  });

  it("records that the passage does not support the answer, and takes it back", async () => {
    const reject = show();
    await userEvent.click(screen.getByRole("button", { name: /Participants receive/ }));

    await userEvent.click(screen.getByRole("button", { name: "This passage does not support the answer" }));
    expect(reject).toHaveBeenCalledWith(true);
  });

  it("offers to undo a note already made", async () => {
    const reject = show({ evidenceRejected: true });
    await userEvent.click(screen.getByRole("button", { name: /Participants receive/ }));

    expect(screen.getByText(/Thanks, noted/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Undo" }));
    expect(reject).toHaveBeenCalledWith(false);
  });

  it("takes no note on a request that can no longer change", async () => {
    show({}, "suggested", true);
    await userEvent.click(screen.getByRole("button", { name: /Participants receive/ }));

    expect(screen.queryByRole("button", { name: /does not support/ })).not.toBeInTheDocument();
  });

  it("warns about low confidence while the suggestion is still to take", async () => {
    show({ confidence: 0.4 });

    expect(screen.getByText("Low confidence")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /Participants receive/ }));
    expect(screen.getByText(/The document is not clear here/)).toBeInTheDocument();
  });
});

describe("ConfirmAnswer", () => {
  it("confirms a suggestion still to take", async () => {
    const confirm = vi.fn();
    render(<ConfirmAnswer status="suggested" onConfirm={confirm} />);

    await userEvent.click(screen.getByRole("button", { name: "Confirm answer" }));

    expect(confirm).toHaveBeenCalled();
  });

  it("is not offered once settled, or on a request that can no longer change", () => {
    const { rerender } = render(<ConfirmAnswer status="accepted" onConfirm={vi.fn()} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();

    rerender(<ConfirmAnswer status="suggested" disabled onConfirm={vi.fn()} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
