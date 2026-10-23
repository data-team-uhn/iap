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

import type { PdfComparisonProps } from "@iap/frontend-commons/pdfViewer/PdfComparison";
import PdfComparisonTestWidget from "@iap/test-data/PdfComparisonTestWidget";

// A stand-in showing what the widget hands the comparison.
vi.mock("@iap/frontend-commons/pdfViewer/PdfComparison", () => ({
  default: ({ onClose, container, ...shown }: PdfComparisonProps) => (
    <div role="dialog">
      <output>{JSON.stringify({ ...shown, inPlace: container !== undefined })}</output>
      <button type="button" onClick={onClose}>Close</button>
    </div>
  ),
}));

function getComparisonProps(): unknown {
  return JSON.parse(screen.getByRole("status").textContent ?? "");
}

describe("PdfComparisonTestWidget", () => {
  it("keeps Compare disabled until both versions are given", async () => {
    render(<PdfComparisonTestWidget />);

    const compare = screen.getByRole("button", { name: "Compare" });
    expect(compare).toBeDisabled();

    await userEvent.type(screen.getByLabelText("Older version URL"), "/v1.pdf");
    expect(compare).toBeDisabled();

    await userEvent.type(screen.getByLabelText("Newer version URL"), "/v2.pdf");
    expect(compare).toBeEnabled();
  });

  it("compares the versions entered, trimmed, with their labels", async () => {
    render(<PdfComparisonTestWidget />);

    await userEvent.type(screen.getByLabelText("Older version URL"), " /v1.pdf ");
    await userEvent.type(screen.getByLabelText("Newer version URL"), " /v2.pdf ");
    await userEvent.type(screen.getByLabelText("Older version label (optional)"), " Version 1 ");
    await userEvent.type(screen.getByLabelText("Newer version label (optional)"), "  ");
    await userEvent.click(screen.getByRole("button", { name: "Compare" }));

    expect(getComparisonProps()).toEqual({
      olderUrl: "/v1.pdf",
      newerUrl: "/v2.pdf",
      olderLabel: "Version 1",
      inPlace: true,
    });
  });

  it("opens the comparison in a dialog when not shown in place, and closes it when asked", async () => {
    render(<PdfComparisonTestWidget />);

    await userEvent.type(screen.getByLabelText("Older version URL"), "/v1.pdf");
    await userEvent.type(screen.getByLabelText("Newer version URL"), "/v2.pdf");
    await userEvent.click(screen.getByRole("checkbox", { name: "Show in place, below the form" }));
    await userEvent.click(screen.getByRole("button", { name: "Compare" }));
    expect(getComparisonProps()).toEqual({ olderUrl: "/v1.pdf", newerUrl: "/v2.pdf", inPlace: false });

    await userEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
