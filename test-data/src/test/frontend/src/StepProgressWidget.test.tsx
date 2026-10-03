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
import { act, fireEvent, render, screen, within } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import StepProgressWidget from "@iap/test-data/StepProgressWidget";

const renderWidget = () => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <StepProgressWidget />
  </ThemeProvider>
);

const advance = () => act(() => {
  vi.advanceTimersByTime(3_000);
});

describe("StepProgressWidget", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("starts on the first step and walks the next ones a few seconds apart", () => {
    renderWidget();
    const status = screen.getByRole("status");

    expect(status).toHaveTextContent("Step 1 of 3. Collect.");
    advance();
    expect(status).toHaveTextContent("Step 2 of 3. Review.");
    advance();
    expect(status).toHaveTextContent("Step 3 of 3. Save.");
    advance();
    expect(status).toHaveTextContent("All 3 steps done.");
    expect(screen.getByRole("button", { name: "Fail this step" })).toBeDisabled();
  });

  it("stops on the step underway when that step is failed", () => {
    renderWidget();
    fireEvent.click(screen.getByRole("button", { name: "Fail this step" }));

    expect(screen.getByRole("alert")).toHaveTextContent("Collect stopped. The demo was asked to fail here.");
    advance();
    expect(screen.getByRole("status")).toHaveTextContent("Step 1 of 3. Collect.");
  });

  it("starts over when played again", () => {
    renderWidget();
    const replay = screen.getByRole("button", { name: "Play again" });
    expect(replay).toBeDisabled();

    advance();
    fireEvent.click(screen.getByRole("button", { name: "Fail this step" }));
    fireEvent.click(replay);

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Step 1 of 3. Collect.");
    expect(replay).toBeDisabled();
    advance();
    expect(screen.getByRole("status")).toHaveTextContent("Step 2 of 3. Review.");
  });

  it("shows the bar in the layout picked", () => {
    renderWidget();

    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Layout" }));
    fireEvent.click(within(screen.getByRole("listbox")).getByRole("option", { name: "Vertical" }));

    expect(screen.getByText("Collect").closest(".MuiStepper-root")).toHaveClass("MuiStepper-vertical");
  });

  it("drops the accent when it is switched off", () => {
    const { container } = renderWidget();

    fireEvent.click(screen.getByRole("switch", { name: "Accent" }));

    expect(container.querySelector("[class*=MuiBox-root]")).toHaveStyle({
      "--iap-step-accent": "var(--mui-palette-primary-main)",
    });
  });
});
