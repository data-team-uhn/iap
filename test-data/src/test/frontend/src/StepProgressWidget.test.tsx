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
import { act, fireEvent, render, screen } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import StepProgressWidget from "@iap/test-data/StepProgressWidget";

const renderWidget = () => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <StepProgressWidget />
  </ThemeProvider>
);

describe("StepProgressWidget", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("starts on the first step and walks the next ones a few seconds apart", () => {
    renderWidget();

    expect(screen.getByText("Collect")).toHaveClass("Mui-active");

    act(() => {
      vi.advanceTimersByTime(3_000);
    });
    expect(screen.getByText("Review")).toHaveClass("Mui-active");

    act(() => {
      vi.advanceTimersByTime(3_000);
    });
    expect(screen.getByText("Save")).toHaveClass("Mui-active");

    act(() => {
      vi.advanceTimersByTime(3_000);
    });
    expect(screen.getByText("Save")).toHaveClass("Mui-completed");
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
  });

  it("stops on the step underway when that step is failed", () => {
    renderWidget();
    fireEvent.click(screen.getByRole("button", { name: "Fail this step" }));

    expect(screen.getByText("This step stopped.")).toBeInTheDocument();
    expect(screen.getByText("Collect")).toHaveClass("Mui-error");

    act(() => {
      vi.advanceTimersByTime(3_000);
    });
    expect(screen.getByText("Collect")).toHaveClass("Mui-error");
    expect(screen.getByText("Review")).not.toHaveClass("Mui-active");
  });
});
