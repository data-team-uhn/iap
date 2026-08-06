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

import { ThemeProvider, createTheme } from "@mui/material/styles";
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { appTheme } from "@iap/frontend-commons/appTheme";
import UserInputAssistant from "@iap/frontend-commons/components/UserInputAssistant";


type AssistantProps = Partial<ComponentProps<typeof UserInputAssistant>>;

// The assistant is a Popper anchored to the input it advises, so every test needs an anchor in the
// document for it to show anything at all.
const renderAssistant = (props: AssistantProps = {}) => {
  const anchor = document.createElement("input");
  document.body.append(anchor);
  const result = render(
    <ThemeProvider theme={appTheme} defaultMode="light">
      <UserInputAssistant anchorEl={anchor} title="Separator detected" {...props}>
        Don&apos;t use comma, press ENTER!
      </UserInputAssistant>
    </ThemeProvider>
  );
  return { ...result, anchor };
};

const setViewportWidth = (width: number) => act(() => {
  window.innerWidth = width;
  window.dispatchEvent(new Event("resize"));
});

describe("UserInputAssistant", () => {
  afterEach(() => {
    document.querySelectorAll("input").forEach(input => input.remove());
    window.innerWidth = 1024;
  });

  it("shows the title and the message", () => {
    renderAssistant();

    expect(screen.getByText("Separator detected")).toBeInTheDocument();
    expect(screen.getByText("Don't use comma, press ENTER!")).toBeInTheDocument();
  });

  it("stays hidden without an anchor to attach to", () => {
    render(
      <ThemeProvider theme={appTheme} defaultMode="light">
        <UserInputAssistant title="Separator detected">Message</UserInputAssistant>
      </ThemeProvider>
    );

    expect(screen.queryByText("Separator detected")).not.toBeInTheDocument();
  });

  it("offers only a dismissal when no action is supplied", () => {
    renderAssistant();

    expect(screen.getByRole("button", { name: "Got it!" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Ignore for now" })).not.toBeInTheDocument();
  });

  it("closes for good once dismissed", async () => {
    const user = userEvent.setup();
    renderAssistant();

    await user.click(screen.getByRole("button", { name: "Got it!" }));

    expect(screen.queryByText("Separator detected")).not.toBeInTheDocument();
  });

  it("offers the suggested action when both a label and a handler are given", async () => {
    const user = userEvent.setup();
    const onAction = vi.fn();
    renderAssistant({ actionLabel: "Separate and add", onAction });

    await user.click(screen.getByRole("button", { name: "Separate and add" }));

    expect(onAction).toHaveBeenCalled();
    // The action does not dismiss the assistant by itself
    expect(screen.getByText("Separator detected")).toBeInTheDocument();
  });

  it("hides the action button when only half of the pair is given", () => {
    renderAssistant({ actionLabel: "Separate and add" });
    expect(screen.queryByRole("button", { name: "Separate and add" })).not.toBeInTheDocument();

    renderAssistant({ onAction: vi.fn() });
    expect(screen.getAllByRole("button", { name: "Got it!" })).toHaveLength(2);
  });

  it("offers to ignore the hint, and reports it", async () => {
    const user = userEvent.setup();
    const onIgnore = vi.fn();
    renderAssistant({ onIgnore });

    await user.click(screen.getByRole("button", { name: "Ignore for now" }));

    expect(onIgnore).toHaveBeenCalled();
    expect(screen.queryByText("Separator detected")).not.toBeInTheDocument();
  });

  it("reports clicks landing outside it", async () => {
    const user = userEvent.setup();
    const onClickAway = vi.fn();
    renderAssistant({ onClickAway });

    await user.click(document.body);

    expect(onClickAway).toHaveBeenCalled();
  });

  it("tolerates a click away with nobody listening", async () => {
    const user = userEvent.setup();
    renderAssistant();

    await user.click(document.body);

    expect(screen.getByText("Separator detected")).toBeInTheDocument();
  });

  it.each([
    ["hint", false],
    ["hint-secondary", false],
    ["success", false],
    ["info", false],
    ["warning", true],
    ["error", true],
  ] as const)("uses the %s accent, warning icon: %s", (variant, warns) => {
    renderAssistant({ variant });

    const icon = document.querySelector(".MuiAvatar-root svg");
    expect(icon).toHaveAttribute("data-testid", warns ? "WarningIcon" : "EmojiObjectsIcon");
  });

  it("sits beside the input on a wide viewport and below it on a narrow one", () => {
    setViewportWidth(1000);
    renderAssistant();
    expect(document.querySelector("[data-popper-placement]")).toHaveAttribute("data-popper-placement", "right");

    setViewportWidth(500);
    expect(document.querySelector("[data-popper-placement]")).toHaveAttribute("data-popper-placement", "bottom");
  });

  // The application theme enables MUI's CSS variables, so the accent normally comes from
  // theme.vars; a theme without them has to keep working.
  it("takes its accent straight off the palette when the theme has no CSS variables", () => {
    const anchor = document.createElement("input");
    document.body.append(anchor);

    render(
      <ThemeProvider theme={createTheme()}>
        <UserInputAssistant anchorEl={anchor} title="Separator detected">Message</UserInputAssistant>
      </ThemeProvider>
    );

    expect(screen.getByText("Separator detected")).toBeInTheDocument();
  });

  it("stops following the viewport once unmounted", () => {
    const removeListener = vi.spyOn(window, "removeEventListener");
    const { unmount } = renderAssistant();

    unmount();

    expect(removeListener).toHaveBeenCalledWith("resize", expect.any(Function));
    removeListener.mockRestore();
  });
});
