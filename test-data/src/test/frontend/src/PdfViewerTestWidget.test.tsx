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
import PdfViewerTestWidget from "@iap/test-data/PdfViewerTestWidget";

const ANCHOR: TextAnchor = {
  start: { page: 2, char: 47 },
  end: { page: 2, char: 64 },
  quote: "on a casual basis",
  before: "Volunteers who,",
  after: ", assist",
};

// A stand-in viewer showing what the widget hands it, and listing the marks with what the widget shows
// under each. Its Comment button, there when the widget takes comments, gives a fixed anchor.
vi.mock("@iap/frontend-commons/pdfViewer/PdfViewer", () => ({
  default: (props: PdfViewerProps) => {
    const { marks, selectedMarkId, onSelectMark, onAnnotate, renderMarkDetails, onClose, container, ...shown } = props;
    return (
      <div role="dialog" aria-label="Viewer">
        <output aria-label="Viewer props">{JSON.stringify({ ...shown, inPlace: container !== undefined })}</output>
        <ul aria-label="Marks">
          {marks.map(mark => (
            <li key={mark.id}>
              <button type="button" onClick={() => onSelectMark?.(mark.id)}>{`Select ${mark.id}`}</button>
              <output aria-label="Mark">{JSON.stringify(mark)}</output>
              {renderMarkDetails?.(mark, mark.id === selectedMarkId)}
            </li>
          ))}
        </ul>
        {onAnnotate === undefined ? null : <button type="button" onClick={() => onAnnotate(ANCHOR)}>Comment</button>}
        <button type="button" onClick={onClose}>Close</button>
      </div>
    );
  },
}));

function getViewerProps(): unknown {
  return JSON.parse(screen.getByRole("status", { name: "Viewer props" }).textContent ?? "");
}

function getMarks(): unknown[] {
  return screen.queryAllByRole("status", { name: "Mark" })
    .map(output => JSON.parse(output.textContent ?? "") as unknown);
}

async function open({ quotes = "", older = "", commenting = true } = {}) {
  render(<PdfViewerTestWidget />);
  await userEvent.type(screen.getByLabelText("PDF URL"), " /some/file.pdf ");
  if (quotes !== "") {
    await userEvent.type(screen.getByLabelText("Text to find (optional)"), quotes);
  }
  if (older !== "") {
    await userEvent.type(screen.getByLabelText("Older version URL (optional)"), older);
  }
  if (!commenting) {
    await userEvent.click(screen.getByLabelText("Comment on passages selected in the document"));
  }
  await userEvent.click(screen.getByRole("button", { name: "Open" }));
}

describe("PdfViewerTestWidget", () => {
  it("keeps Open disabled until a URL is given", async () => {
    render(<PdfViewerTestWidget />);

    expect(screen.getByRole("button", { name: "Open" })).toBeDisabled();
    await userEvent.type(screen.getByLabelText("PDF URL"), "/some/file.pdf");
    expect(screen.getByRole("button", { name: "Open" })).toBeEnabled();
  });

  it("opens the viewer with the entered values, trimmed", async () => {
    render(<PdfViewerTestWidget />);

    await userEvent.type(screen.getByLabelText("PDF URL"), " /some/file.pdf ");
    await userEvent.type(screen.getByLabelText("Text to find (optional)"), " a quote to look for ");
    await userEvent.type(screen.getByLabelText("Page (optional)"), "2");
    await userEvent.type(screen.getByLabelText("Title (optional)"), " Memo ");
    await userEvent.click(screen.getByLabelText("Comment on passages selected in the document"));
    await userEvent.click(screen.getByRole("button", { name: "Open" }));

    expect(getViewerProps()).toEqual({ url: "/some/file.pdf", page: 2, title: "Memo", inPlace: true });
    expect(getMarks()).toEqual([ { id: "passage-1", quote: "a quote to look for", page: 2, kind: "assistant" } ]);
    expect(screen.queryByRole("button", { name: "Comment" })).not.toBeInTheDocument();
  });

  it("leaves out a blank page and title, and closes the viewer when asked", async () => {
    render(<PdfViewerTestWidget />);

    await userEvent.type(screen.getByLabelText("PDF URL"), "/some/file.pdf");
    await userEvent.type(screen.getByLabelText("Title (optional)"), "  ");
    await userEvent.click(screen.getByRole("button", { name: "Open" }));
    expect(getViewerProps()).toEqual({
      url: "/some/file.pdf",
      markName: { one: "comment", many: "comments" },
      inPlace: true,
    });

    await userEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("opens the viewer in a dialog when it is not shown in place", async () => {
    render(<PdfViewerTestWidget />);

    await userEvent.type(screen.getByLabelText("PDF URL"), "/some/file.pdf");
    await userEvent.click(screen.getByLabelText("Show in place, below the form"));
    await userEvent.click(screen.getByRole("button", { name: "Open" }));

    expect(getViewerProps()).toMatchObject({ inPlace: false });
  });

  it("marks the passage as a person's when asked", async () => {
    render(<PdfViewerTestWidget />);

    await userEvent.type(screen.getByLabelText("PDF URL"), "/some/file.pdf");
    await userEvent.type(screen.getByLabelText("Text to find (optional)"), "a quote to look for");
    await userEvent.click(screen.getByRole("combobox", { name: "Marked by" }));
    await userEvent.click(screen.getByRole("option", { name: "A person" }));
    await userEvent.click(screen.getByRole("button", { name: "Open" }));

    expect(getMarks()).toMatchObject([ { kind: "annotation" } ]);
  });

  it("marks each line as a passage of its own, leaving out blank ones, and both kinds in turn when asked", async () => {
    render(<PdfViewerTestWidget />);

    await userEvent.type(screen.getByLabelText("PDF URL"), "/some/file.pdf");
    const lines = "first passage{Enter}{Enter} second passage {Enter}third";
    await userEvent.type(screen.getByLabelText("Text to find (optional)"), lines);
    await userEvent.click(screen.getByRole("combobox", { name: "Marked by" }));
    await userEvent.click(screen.getByRole("option", { name: "Both, in turn" }));
    await userEvent.click(screen.getByRole("button", { name: "Open" }));

    expect(getMarks()).toMatchObject([
      { id: "passage-1", quote: "first passage", kind: "assistant" },
      { id: "passage-2", quote: "second passage", kind: "annotation" },
      { id: "passage-3", quote: "third", kind: "assistant" },
    ]);
  });

  it("compares the PDF with an older version when given one", async () => {
    await open({ older: " /some/v1.pdf ", commenting: false });

    expect(getViewerProps()).toEqual({ url: "/some/file.pdf", compareWith: { url: "/some/v1.pdf" }, inPlace: true });
  });

  it("calls the marks passages or comments by what they are, or marks once there are both", async () => {
    await open({ quotes: "a quote", older: "/some/v1.pdf" });
    expect(getViewerProps()).not.toHaveProperty("markName");

    await userEvent.click(screen.getByRole("button", { name: "Comment" }));
    await userEvent.type(screen.getByRole("textbox", { name: "Comment" }), "Why?");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(getViewerProps()).toMatchObject({ markName: { one: "mark", many: "marks" } });
  });

  it("keeps showing what was opened while the form is edited", async () => {
    await open();
    await userEvent.type(screen.getByLabelText("PDF URL"), "x");

    expect(getViewerProps()).toMatchObject({ url: "/some/file.pdf" });
  });

  describe("comments", () => {
    it("marks a passage selected to comment on, read back from how a review comment keeps it", async () => {
      await open({ quotes: "a quote" });

      await userEvent.click(screen.getByRole("button", { name: "Comment" }));

      expect(getMarks()).toEqual([
        expect.objectContaining({ id: "passage-1" }),
        { id: "comment-1", anchor: ANCHOR, kind: "annotation" },
      ]);
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
      expect(screen.getByRole("dialog", { name: "Viewer" })).toBeInTheDocument();
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

    it("starts over when the viewer is opened again", async () => {
      await open();
      await userEvent.click(screen.getByRole("button", { name: "Comment" }));
      await userEvent.click(screen.getByRole("button", { name: "Close" }));

      expect(screen.queryByRole("dialog", { name: "Viewer" })).not.toBeInTheDocument();

      await userEvent.click(screen.getByRole("button", { name: "Open" }));
      expect(getMarks()).toEqual([]);
    });
  });
});
