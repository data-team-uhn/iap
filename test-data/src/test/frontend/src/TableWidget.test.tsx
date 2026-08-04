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
import { render, screen, within } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import TableWidget from "@iap/test-data/TableWidget";

const COLUMNS = ["ID", "Title", "Status", "Updated"];

const PROPOSALS = [
  ["PRO-1042", "Genomic markers in early-onset diabetes", "Approved", "2026-07-16"],
  ["PRO-1041", "AI-assisted triage in the emergency department", "In review", "2026-07-15"],
  ["PRO-1038", "Longitudinal cohort of post-transplant outcomes", "In review", "2026-07-14"],
  ["PRO-1035", "Wearable data for cardiac rehabilitation", "Draft", "2026-07-12"],
  ["PRO-1031", "Health equity in remote oncology follow-up", "Approved", "2026-07-09"],
];

const renderWidget = () => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <TableWidget />
  </ThemeProvider>
);

// The header is the first row; the rest are the proposals.
const bodyRows = () => screen.getAllByRole("row").slice(1);

const cellsOf = (row: HTMLElement) => within(row).getAllByRole("cell").map(cell => cell.textContent);

describe("TableWidget", () => {
  it("labels the columns", () => {
    renderWidget();

    expect(screen.getAllByRole("columnheader").map(header => header.textContent)).toEqual(COLUMNS);
  });

  // One assertion over the whole grid: it pins the number of rows, the content of every cell, the
  // order of the columns within each row, and the order of the rows themselves.
  it("lists every proposal, cell for cell", () => {
    renderWidget();

    expect(bodyRows().map(cellsOf)).toEqual(PROPOSALS);
  });

  it("puts the most recently updated proposal at the top", () => {
    renderWidget();

    const dates = bodyRows().map(row => cellsOf(row).at(-1));

    expect(dates).toEqual([...dates].sort().reverse());
  });

  // Numbers read better against a common right edge, and the header has to travel with its column.
  it("right-aligns the update dates and their header", () => {
    renderWidget();

    expect(screen.getByRole("columnheader", { name: "Updated" })).toHaveStyle({ textAlign: "right" });
    bodyRows().forEach(row => {
      expect(within(row).getAllByRole("cell").at(-1)).toHaveStyle({ textAlign: "right" });
    });
  });

  // The widget has to fit a dashboard tile, which the roomier default padding would not.
  it("uses the dense row height so the table fits a tile", () => {
    renderWidget();

    expect(screen.getAllByRole("columnheader")[0]).toHaveClass("MuiTableCell-sizeSmall");
  });

  // The dashboard draws the frame and the "Recent proposals" title around the widget, so a heading
  // in here would duplicate it and add a stray entry to the document outline.
  it("contributes no heading of its own", () => {
    renderWidget();

    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
  });
});
