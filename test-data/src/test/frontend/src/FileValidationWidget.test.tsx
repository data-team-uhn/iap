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

import { ThemeProvider } from "@mui/material/styles";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import { validateUpload } from "@iap/frontend-commons/fileValidation";
import FileValidationWidget from "@iap/test-data/FileValidationWidget";

// The rules have their own tests; this one checks what the widget asks and shows
vi.mock("@iap/frontend-commons/fileValidation", async importOriginal => ({
  ...await importOriginal<typeof import("@iap/frontend-commons/fileValidation")>(),
  validateUpload: vi.fn(),
}));

const validate = vi.mocked(validateUpload);

const renderWidget = () => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <FileValidationWidget />
  </ThemeProvider>
);

function pick(name: string) {
  const file = new File([ "x" ], name);
  // The input is hidden from sight, not from the label that opens it
  fireEvent.change(screen.getByLabelText("Pick a file"), { target: { files: [ file ] } });
  return file;
}

describe("FileValidationWidget", () => {
  beforeEach(() => {
    validate.mockReset();
  });

  it("waits for a file before checking anything", () => {
    renderWidget();

    expect(screen.getByText("No file picked yet")).toBeInTheDocument();
    expect(validate).not.toHaveBeenCalled();
  });

  it("says a file passes when nothing is wrong with it", async () => {
    validate.mockResolvedValue(undefined);
    renderWidget();

    const file = pick("proposal.pdf");

    expect(await screen.findByText("proposal.pdf passes every check.")).toBeInTheDocument();
    expect(validate).toHaveBeenCalledWith(file, [], { maxFileSize: 50 * 1024 * 1024, maxPdfPages: 500 });
  });

  it("shows why a file is refused", async () => {
    validate.mockResolvedValue("proposal.pdf: It is encrypted with a password.");
    renderWidget();

    pick("proposal.pdf");

    expect(await screen.findByRole("alert")).toHaveTextContent("It is encrypted with a password.");
  });

  it("shows that it is checking until the answer comes", () => {
    validate.mockReturnValue(new Promise(() => undefined));
    renderWidget();

    pick("proposal.pdf");

    expect(screen.getByRole("status")).toHaveTextContent("Checking proposal.pdf");
  });

  it("checks the same file again under the types and limits picked", async () => {
    validate.mockResolvedValue(undefined);
    renderWidget();
    const file = pick("proposal.pdf");
    await screen.findByText("proposal.pdf passes every check.");

    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Accepts" }));
    fireEvent.click(within(screen.getByRole("listbox")).getByRole("option", { name: "PDF only" }));
    fireEvent.change(screen.getByLabelText("Size limit (MB)"), { target: { value: "2" } });
    fireEvent.change(screen.getByLabelText("Page limit"), { target: { value: "10" } });

    await screen.findByText("proposal.pdf passes every check.");
    expect(validate).toHaveBeenLastCalledWith(file, [ "application/pdf" ], {
      maxFileSize: 2 * 1024 * 1024,
      maxPdfPages: 10,
    });
  });

  // An empty or nonsense limit is not a limit of zero
  it("falls back to the default for a limit left empty", async () => {
    validate.mockResolvedValue(undefined);
    renderWidget();
    const file = pick("proposal.pdf");

    fireEvent.change(screen.getByLabelText("Size limit (MB)"), { target: { value: "" } });
    fireEvent.change(screen.getByLabelText("Page limit"), { target: { value: "0" } });

    await screen.findByText("proposal.pdf passes every check.");
    expect(validate).toHaveBeenLastCalledWith(file, [], { maxFileSize: undefined, maxPdfPages: undefined });
  });

  // Each check reads the whole file, so typing "400" runs one check, not three
  it("checks once typing stops, not on every keystroke", async () => {
    validate.mockResolvedValue(undefined);
    renderWidget();
    pick("proposal.pdf");
    await screen.findByText("proposal.pdf passes every check.");

    // Keys 100 ms apart, as a person types them
    for (const value of [ "4", "40", "400" ]) {
      fireEvent.change(screen.getByLabelText("Page limit"), { target: { value } });
      await act(() => new Promise(resolve => setTimeout(resolve, 100)));
    }

    await screen.findByText("proposal.pdf passes every check.");
    expect(validate).toHaveBeenCalledTimes(2);
    expect(validate).toHaveBeenLastCalledWith(expect.anything(), [], expect.objectContaining({ maxPdfPages: 400 }));
  });

  it("shows a check that failed outright", async () => {
    validate.mockRejectedValue(new Error("worker gone"));
    renderWidget();

    pick("proposal.pdf");

    expect(await screen.findByRole("alert")).toHaveTextContent("worker gone");
  });

  // An answer for a file no longer picked must not overwrite the one for the file that is
  it("ignores the answer to a check it has moved on from", async () => {
    let finishFirst: (problem: string | undefined) => void = () => undefined;
    validate
      .mockReturnValueOnce(new Promise(resolve => {
        finishFirst = resolve;
      }))
      .mockResolvedValueOnce(undefined);
    renderWidget();

    pick("first.pdf");
    await waitFor(() => expect(validate).toHaveBeenCalledTimes(1));
    pick("second.pdf");
    await screen.findByText("second.pdf passes every check.");
    await act(async () => {
      finishFirst("first.pdf: It is damaged.");
      await Promise.resolve();
    });

    expect(screen.queryByText(/damaged/)).not.toBeInTheDocument();
    expect(screen.getByText("second.pdf passes every check.")).toBeInTheDocument();
  });
});
