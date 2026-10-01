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

import PdfViewerTestWidget from "@iap/frontend-commons/components/PdfViewerTestWidget";

vi.mock("pdfjs-dist", () => ({
  GlobalWorkerOptions: { workerSrc: "" },
  getDocument: () => ({
    destroy: () => undefined,
    promise: new Promise(() => undefined),
  }),
}));

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

  it("opens the PdfViewer with the entered values", async () => {
    render(<PdfViewerTestWidget />);

    await userEvent.type(screen.getByLabelText("PDF URL"), "/some/file.pdf");
    await userEvent.type(screen.getByLabelText("Quote to find"), "a quote to look for");
    await userEvent.type(screen.getByLabelText("Citation (optional)"), "p. 1");
    await userEvent.click(screen.getByRole("button", { name: "Open" }));

    expect(await screen.findByRole("heading", { name: "p. 1" })).toBeInTheDocument();
    expect(screen.getByText("“a quote to look for”")).toBeInTheDocument();
  });
});
