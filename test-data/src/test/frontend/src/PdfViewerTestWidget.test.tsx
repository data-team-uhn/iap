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
import PdfViewerTestWidget from "@iap/test-data/PdfViewerTestWidget";

// The viewer has tests of its own; this stand-in shows what the widget hands it.
vi.mock("@iap/frontend-commons/pdfViewer/PdfViewer", () => ({
  default: ({ onClose, container, ...shown }: PdfViewerProps) => (
    <div role="dialog">
      <output>{JSON.stringify({ ...shown, inPlace: container !== undefined })}</output>
      <button type="button" onClick={onClose}>Close</button>
    </div>
  ),
}));

function getViewerProps(): unknown {
  return JSON.parse(screen.getByRole("status").textContent ?? "");
}

describe("PdfViewerTestWidget", () => {
  it("keeps Open disabled until both a URL and a quote are given", async () => {
    render(<PdfViewerTestWidget />);

    const open = screen.getByRole("button", { name: "Open" });
    expect(open).toBeDisabled();

    await userEvent.type(screen.getByLabelText("PDF URL"), "/some/file.pdf");
    expect(open).toBeDisabled();

    await userEvent.type(screen.getByLabelText("Text to find"), "a quote to look for");
    expect(open).toBeEnabled();
  });

  it("opens the viewer with the entered values, trimmed", async () => {
    render(<PdfViewerTestWidget />);

    await userEvent.type(screen.getByLabelText("PDF URL"), " /some/file.pdf ");
    await userEvent.type(screen.getByLabelText("Text to find"), " a quote to look for ");
    await userEvent.type(screen.getByLabelText("Page (optional)"), "2");
    await userEvent.type(screen.getByLabelText("Title (optional)"), " Memo ");
    await userEvent.click(screen.getByRole("button", { name: "Open" }));

    expect(getViewerProps()).toEqual({
      url: "/some/file.pdf",
      page: 2,
      quote: "a quote to look for",
      markedBy: "assistant",
      title: "Memo",
      inPlace: true,
    });
  });

  it("leaves out a blank page and title, and closes the viewer when asked", async () => {
    render(<PdfViewerTestWidget />);

    await userEvent.type(screen.getByLabelText("PDF URL"), "/some/file.pdf");
    await userEvent.type(screen.getByLabelText("Text to find"), "a quote to look for");
    await userEvent.type(screen.getByLabelText("Title (optional)"), "  ");
    await userEvent.click(screen.getByRole("button", { name: "Open" }));
    expect(getViewerProps()).toEqual({
      url: "/some/file.pdf",
      quote: "a quote to look for",
      markedBy: "assistant",
      inPlace: true,
    });

    await userEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("opens the viewer in a dialog when it is not shown in place", async () => {
    render(<PdfViewerTestWidget />);

    await userEvent.type(screen.getByLabelText("PDF URL"), "/some/file.pdf");
    await userEvent.type(screen.getByLabelText("Text to find"), "a quote to look for");
    await userEvent.click(screen.getByLabelText("Show in place, below the form"));
    await userEvent.click(screen.getByRole("button", { name: "Open" }));

    expect(getViewerProps()).toMatchObject({ inPlace: false });
  });

  it("marks the passage as a person's when asked", async () => {
    render(<PdfViewerTestWidget />);

    await userEvent.type(screen.getByLabelText("PDF URL"), "/some/file.pdf");
    await userEvent.type(screen.getByLabelText("Text to find"), "a quote to look for");
    await userEvent.click(screen.getByRole("combobox", { name: "Marked by" }));
    await userEvent.click(screen.getByRole("option", { name: "A person" }));
    await userEvent.click(screen.getByRole("button", { name: "Open" }));

    expect(getViewerProps()).toMatchObject({ markedBy: "annotation" });
  });

  it("keeps showing what was opened while the form is edited", async () => {
    render(<PdfViewerTestWidget />);

    await userEvent.type(screen.getByLabelText("PDF URL"), "/some/file.pdf");
    await userEvent.type(screen.getByLabelText("Text to find"), "a quote to look for");
    await userEvent.click(screen.getByRole("button", { name: "Open" }));
    await userEvent.type(screen.getByLabelText("PDF URL"), "x");

    expect(getViewerProps()).toMatchObject({ url: "/some/file.pdf" });
  });
});
