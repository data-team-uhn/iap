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

import type { PdfQuotePassage } from "@iap/frontend-commons/components/PdfViewer";
import PdfViewerTestWidget from "@iap/test-data/PdfViewerTestWidget";

// The viewer has tests of its own; this stand-in shows what the widget hands it.
vi.mock("@iap/frontend-commons/components/PdfViewer", () => ({
  default: ({ passage, onClose }: { passage: PdfQuotePassage; onClose: () => void }) => (
    <div role="dialog">
      <output>{JSON.stringify(passage)}</output>
      <button type="button" onClick={onClose}>Close</button>
    </div>
  ),
}));

function getPassage(): unknown {
  return JSON.parse(screen.getByRole("status").textContent ?? "");
}

describe("PdfViewerTestWidget", () => {
  it("keeps Open disabled until both a URL and a quote are given", async () => {
    render(<PdfViewerTestWidget />);

    const open = screen.getByRole("button", { name: "Open" });
    expect(open).toBeDisabled();

    await userEvent.type(screen.getByLabelText("PDF URL"), "/some/file.pdf");
    expect(open).toBeDisabled();

    await userEvent.type(screen.getByLabelText("Quote to find"), "a quote to look for");
    expect(open).toBeEnabled();
  });

  it("opens the viewer with the entered values, trimmed", async () => {
    render(<PdfViewerTestWidget />);

    await userEvent.type(screen.getByLabelText("PDF URL"), " /some/file.pdf ");
    await userEvent.type(screen.getByLabelText("Quote to find"), " a quote to look for ");
    await userEvent.type(screen.getByLabelText("Citation (optional)"), " p. 1 ");
    await userEvent.click(screen.getByRole("button", { name: "Open" }));

    expect(getPassage()).toEqual({ quote: "a quote to look for", source: "/some/file.pdf", cite: "p. 1" });
  });

  it("leaves out a blank citation, and closes the viewer when asked", async () => {
    render(<PdfViewerTestWidget />);

    await userEvent.type(screen.getByLabelText("PDF URL"), "/some/file.pdf");
    await userEvent.type(screen.getByLabelText("Quote to find"), "a quote to look for");
    await userEvent.type(screen.getByLabelText("Citation (optional)"), "  ");
    await userEvent.click(screen.getByRole("button", { name: "Open" }));
    expect(getPassage()).toEqual({ quote: "a quote to look for", source: "/some/file.pdf" });

    await userEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
