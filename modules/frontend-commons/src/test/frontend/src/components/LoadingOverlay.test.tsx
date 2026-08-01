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
import { render, screen } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";

import type { ComponentProps } from "react";

const renderOverlay = (props: Partial<ComponentProps<typeof LoadingOverlay>> = {}) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <LoadingOverlay open {...props} />
  </ThemeProvider>
);

describe("LoadingOverlay", () => {
  it("spins without a message when none is given", () => {
    renderOverlay();

    expect(screen.getByRole("progressbar")).toBeInTheDocument();
    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
  });

  it("shows the message it is given", () => {
    renderOverlay({ message: "Saving your work" });

    expect(screen.getByText("Saving your work")).toBeInTheDocument();
  });

  it("spins indeterminately when there is no progress to report", () => {
    renderOverlay();

    expect(screen.getByRole("progressbar")).not.toHaveAttribute("aria-valuenow");
  });

  it("reports the progress it is given", () => {
    renderOverlay({ progress: 42 });

    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "42");
  });

  it("treats zero progress as a real value, not as absent", () => {
    renderOverlay({ progress: 0 });

    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "0");
  });

  it("stays out of the way when closed", () => {
    renderOverlay({ open: false });

    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
  });
});
