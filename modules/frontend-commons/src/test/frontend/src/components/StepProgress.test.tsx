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

import type { ComponentProps } from "react";

import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import StepProgress, { type StepProgressLayout } from "@iap/frontend-commons/components/StepProgress";

const STEPS = [ "Collect", "Review", "Save" ];

interface Props {
  activeStep?: number;
  error?: string;
  layout?: ComponentProps<typeof StepProgress>["layout"];
  color?: ComponentProps<typeof StepProgress>["color"];
}

// Horizontal unless a layout is given, even an undefined one to leave the default to the component.
const tree = (props: Props) => (
  <ThemeProvider theme={appTheme} defaultMode="light">
    <StepProgress
      steps={STEPS}
      activeStep={props.activeStep ?? 0}
      error={props.error}
      layout={"layout" in props ? props.layout : "horizontal"}
      color={props.color}
    />
  </ThemeProvider>
);

const renderSteps = (props: Props = {}) => {
  const view = render(tree(props));
  return { ...view, rerenderSteps: (next: Props) => view.rerender(tree(next)) };
};

const getStepElement = (label: string) => screen.getByText(label).closest(".MuiStep-root");

// MUI reads the breakpoint through matchMedia, which jsdom does not implement; without a stand-in
// useMediaQuery just reports false, so only xs matches.
const stubMatchMedia = (matching: (query: string) => boolean) => vi.stubGlobal("matchMedia", (query: string) => ({
  matches: matching(query),
  media: query,
  onchange: null,
  addListener: () => { /* deprecated, unused */ },
  removeListener: () => { /* deprecated, unused */ },
  addEventListener: () => { /* no live changes in these tests */ },
  removeEventListener: () => { /* no live changes in these tests */ },
  dispatchEvent: () => false,
}));

describe("StepProgress", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  describe.each<StepProgressLayout>([ "horizontal", "vertical" ])("laid out %s", layout => {
    it("names every step and wears a ring on the one underway", () => {
      renderSteps({ activeStep: 1, layout });

      expect(screen.getByText("Collect")).toHaveClass("Mui-completed");
      expect(screen.getByText("Review")).toHaveClass("Mui-active");
      expect(screen.getByText("Save")).toBeInTheDocument();
      expect(document.querySelector(".StepProgress-ring")).toBeInTheDocument();
      expect(screen.getByRole("status")).toHaveTextContent("Step 2 of 3. Review.");
    });

    it("drops the ring once every step is done", () => {
      renderSteps({ activeStep: STEPS.length, layout });

      expect(document.querySelector(".StepProgress-ring")).not.toBeInTheDocument();
      expect(screen.getByText("Save")).toHaveClass("Mui-completed");
      expect(screen.getByRole("status")).toHaveTextContent("All 3 steps done.");
    });

    it("alerts that the failed step stopped, and why", () => {
      renderSteps({ activeStep: 1, error: "Couldn't read the file.", layout });

      expect(screen.getByRole("alert")).toHaveTextContent("Review stopped. Couldn't read the file.");
      expect(screen.getByText("Review")).toHaveClass("Mui-error");
      expect(document.querySelector(".StepProgress-ring")).not.toBeInTheDocument();
    });

    it("marks the step that ended and the one arriving when the bar moves forward", () => {
      const { rerenderSteps } = renderSteps({ activeStep: 1, layout });
      expect(getStepElement("Review")).not.toHaveClass("StepProgress-arriving");

      rerenderSteps({ activeStep: 2, layout });

      expect(getStepElement("Review")).toHaveClass("StepProgress-ended");
      expect(getStepElement("Save")).toHaveClass("StepProgress-arriving");
      expect(getStepElement("Collect")).not.toHaveClass("StepProgress-ended");
    });

    it("marks nothing when the bar moves back or a step fails", () => {
      const { rerenderSteps } = renderSteps({ activeStep: 1, layout });
      rerenderSteps({ activeStep: 2, layout });

      rerenderSteps({ activeStep: 2, error: "Stopped.", layout });
      expect(getStepElement("Save")).not.toHaveClass("StepProgress-arriving");

      rerenderSteps({ activeStep: 0, layout });
      expect(getStepElement("Collect")).not.toHaveClass("StepProgress-arriving");
    });
  });

  it("ignores a failure once every step is done", () => {
    renderSteps({ activeStep: STEPS.length, error: "Too late." });

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByText("Save")).toHaveClass("Mui-completed");
    expect(screen.getByRole("status")).toHaveTextContent("All 3 steps done.");
  });

  it("names the failed step only to screen readers when the message hangs under it", () => {
    renderSteps({ activeStep: 1, error: "Couldn't read the file.", layout: "vertical" });

    expect(screen.getByText("Review stopped.")).toHaveStyle({ position: "absolute" });
  });

  describe("laid out compact", () => {
    it("shows only the step underway, with how far along it is", () => {
      renderSteps({ activeStep: 1, layout: "compact" });

      expect(screen.getByText("Review")).toBeInTheDocument();
      expect(screen.getByText("2/3")).toBeInTheDocument();
      expect(screen.queryByText("Collect")).not.toBeInTheDocument();
      expect(screen.queryByText("Save")).not.toBeInTheDocument();
      expect(document.querySelector(".StepProgress-ring")).toBeInTheDocument();
    });

    it("keeps the last step, checked, once every step is done", () => {
      renderSteps({ activeStep: STEPS.length, layout: "compact" });

      expect(screen.getByText("Save")).toBeInTheDocument();
      expect(screen.getByTestId("CheckCircleIcon")).toBeInTheDocument();
      expect(document.querySelector(".StepProgress-ring")).not.toBeInTheDocument();
    });

    it("alerts that the failed step stopped, and why", () => {
      renderSteps({ activeStep: 1, error: "Couldn't read the file.", layout: "compact" });

      expect(screen.getByRole("alert")).toHaveTextContent("Review stopped. Couldn't read the file.");
      expect(screen.getByTestId("WarningIcon")).toBeInTheDocument();
      expect(document.querySelector(".StepProgress-ring")).not.toBeInTheDocument();
    });

    it("checks the step that ended before the next one shows when the bar moves forward", () => {
      const { rerenderSteps } = renderSteps({ activeStep: 1, layout: "compact" });

      rerenderSteps({ activeStep: 2, layout: "compact" });

      const leaving = screen.getByText("Review").closest(".StepProgress-leaving");
      expect(leaving).toHaveAttribute("aria-hidden", "true");
      expect(leaving).toContainElement(screen.getByTestId("CheckCircleIcon"));
      expect(screen.getByText("Save").closest(".StepProgress-entering")).toBeInTheDocument();
      expect(screen.getByText("3/3")).toBeInTheDocument();
    });

    it("only checks the last step when every step is done", () => {
      const { rerenderSteps } = renderSteps({ activeStep: 2, layout: "compact" });

      rerenderSteps({ activeStep: STEPS.length, layout: "compact" });

      expect(screen.getByText("Save").closest(".StepProgress-ended")).toBeInTheDocument();
      expect(document.querySelector(".StepProgress-leaving")).not.toBeInTheDocument();
    });
  });

  describe("by default", () => {
    it("keeps to one line on a phone", () => {
      renderSteps({ activeStep: 1, layout: undefined });

      expect(screen.getByText("2/3")).toBeInTheDocument();
    });

    it("shows every step on a wider screen", () => {
      stubMatchMedia(() => true);
      renderSteps({ activeStep: 1, layout: undefined });

      expect(screen.getByText("Collect")).toBeInTheDocument();
      expect(screen.queryByText("2/3")).not.toBeInTheDocument();
    });
  });

  it.each([
    [ "secondary by default", undefined, "secondary" ],
    [ "the colour asked for", "primary", "primary" ],
  ] as const)("accents the step underway in %s", (_name, color, palette) => {
    const { container } = renderSteps({ activeStep: 1, color });

    expect(container.firstElementChild).toHaveStyle({
      "--iap-step-accent": `var(--mui-palette-${palette}-main)`,
    });
  });

  it("takes the layout of the widest breakpoint given that the screen reaches", () => {
    stubMatchMedia(query => !query.includes(`${appTheme.breakpoints.values.lg}px`)
      && !query.includes(`${appTheme.breakpoints.values.xl}px`));
    renderSteps({ activeStep: 1, layout: { sm: "vertical", lg: "compact" } });

    expect(screen.getByText("Collect").closest(".MuiStepper-root")).toHaveClass("MuiStepper-vertical");
  });
});
