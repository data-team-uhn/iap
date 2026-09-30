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
import StepProgress from "@iap/frontend-commons/components/StepProgress";

const STEPS = [ "Collect", "Review", "Save" ];

const renderSteps = (props: Partial<{ activeStep: number; error: { step: number; message: string } }> = {}) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <StepProgress steps={STEPS} activeStep={props.activeStep ?? 0} error={props.error} />
  </ThemeProvider>
);

describe("StepProgress", () => {
  it("names every step it is given", () => {
    renderSteps();

    expect(screen.getByText("Collect")).toBeInTheDocument();
    expect(screen.getByText("Review")).toBeInTheDocument();
    expect(screen.getByText("Save")).toBeInTheDocument();
  });

  it("wears a ring on the step that is underway", () => {
    renderSteps({ activeStep: 1 });

    expect(screen.getByRole("progressbar")).toBeInTheDocument();
    expect(screen.getByText("Review")).toHaveClass("Mui-active");
    expect(screen.getByRole("status")).toBeInTheDocument();
  });

  it("drops the ring once every step is done", () => {
    renderSteps({ activeStep: STEPS.length });

    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
    expect(screen.getByText("Save")).toHaveClass("Mui-completed");
  });

  it("hangs the failure under the step that stopped", () => {
    renderSteps({ activeStep: 1, error: { step: 1, message: "This step stopped." } });

    expect(screen.getByText("This step stopped.")).toBeInTheDocument();
    expect(screen.getByText("Review")).toHaveClass("Mui-error");
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });
});
