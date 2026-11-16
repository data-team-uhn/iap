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
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import type { TextAnchor } from "@iap/frontend-commons/pdfViewer/marksModel";
import type { PdfViewerProps } from "@iap/frontend-commons/pdfViewer/PdfViewer";
import PdfAnnotationsTestWidget from "@iap/test-data/PdfAnnotationsTestWidget";

const ANCHOR: TextAnchor = {
  start: { page: 2, char: 47 },
  end: { page: 2, char: 64 },
  quote: "on a casual basis",
  before: "Volunteers who,",
  after: ", assist",
};

// A stand-in for the viewer, listing the marks it is given with what the widget shows under each. Its
// Comment button gives a fixed anchor.
vi.mock("@iap/frontend-commons/pdfViewer/PdfViewer", () => ({
  default: ({ marks, selectedMarkId, onSelectMark, onAnnotate, renderMarkDetails, onClose }: PdfViewerProps) => (
    <div role="region" aria-label="Viewer">
      <ul>
        {marks.map(mark => (
          <li key={mark.id}>
            <button type="button" onClick={() => onSelectMark?.(mark.id)}>{`Select ${mark.id}`}</button>
            <output>{JSON.stringify(mark)}</output>
            {renderMarkDetails?.(mark, mark.id === selectedMarkId)}
          </li>
        ))}
      </ul>
      <button type="button" onClick={() => onAnnotate?.(ANCHOR)}>Comment</button>
      <button type="button" onClick={onClose}>Close</button>
    </div>
  ),
}));

async function open() {
  render(<PdfAnnotationsTestWidget />);
  await userEvent.type(screen.getByLabelText("PDF URL"), " /some/file.pdf ");
  await userEvent.click(screen.getByRole("button", { name: "Open" }));
}

function getMarks(): unknown[] {
  return screen.queryAllByRole("status").map(output => JSON.parse(output.textContent ?? "") as unknown);
}

describe("PdfAnnotationsTestWidget", () => {
  it("keeps Open disabled until a URL is given", async () => {
    render(<PdfAnnotationsTestWidget />);

    expect(screen.getByRole("button", { name: "Open" })).toBeDisabled();
    await userEvent.type(screen.getByLabelText("PDF URL"), "/some/file.pdf");
    expect(screen.getByRole("button", { name: "Open" })).toBeEnabled();
  });

  it("marks a passage selected to comment on, read back from how a review comment keeps it", async () => {
    await open();

    await userEvent.click(screen.getByRole("button", { name: "Comment" }));

    expect(getMarks()).toEqual([ { id: "comment-1", anchor: ANCHOR, kind: "annotation" } ]);
    expect(screen.getByRole("textbox", { name: "Comment" })).toHaveFocus();
  });

  it("saves the comment written under the passage, and shows it there", async () => {
    await open();
    await userEvent.click(screen.getByRole("button", { name: "Comment" }));

    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    await userEvent.type(screen.getByRole("textbox", { name: "Comment" }), " Is this every volunteer? ");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(screen.queryByRole("textbox", { name: "Comment" })).not.toBeInTheDocument();
    expect(within(screen.getByRole("listitem")).getByText("Is this every volunteer?")).toBeInTheDocument();
  });

  it("drops a comment given up, from its button or on Escape", async () => {
    await open();
    await userEvent.click(screen.getByRole("button", { name: "Comment" }));
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(getMarks()).toEqual([]);

    await userEvent.click(screen.getByRole("button", { name: "Comment" }));
    await userEvent.keyboard("{Escape}");

    expect(getMarks()).toEqual([]);
    expect(screen.getByRole("region", { name: "Viewer" })).toBeInTheDocument();
  });

  it("keeps one comment not saved at a time, and says so when another passage is selected", async () => {
    await open();
    await userEvent.click(screen.getByRole("button", { name: "Comment" }));
    await userEvent.type(screen.getByRole("textbox", { name: "Comment" }), "First");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    await userEvent.click(screen.getByRole("button", { name: "Comment" }));
    await userEvent.click(screen.getByRole("button", { name: "Comment" }));

    expect(getMarks()).toEqual([
      expect.objectContaining({ id: "comment-1" }),
      expect.objectContaining({ id: "comment-3" }),
    ]);

    await userEvent.click(screen.getByRole("button", { name: "Select comment-1" }));

    expect(screen.getByText("A comment not saved yet.")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Select comment-3" }));
    await userEvent.type(screen.getByRole("textbox", { name: "Comment" }), "Second");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(screen.getAllByRole("listitem").map(item => item.textContent)).toEqual([
      expect.stringContaining("First"),
      expect.stringContaining("Second"),
    ]);
  });

  it("starts over when the viewer is closed and opened again", async () => {
    await open();
    await userEvent.click(screen.getByRole("button", { name: "Comment" }));
    await userEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(screen.queryByRole("region", { name: "Viewer" })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Open" }));
    expect(getMarks()).toEqual([]);
  });
});
