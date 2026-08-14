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

import { DialogContent } from "@mui/material";
import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { appTheme } from "@iap/frontend-commons/appTheme";
import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";


type DialogProps = Partial<ComponentProps<typeof ResponsiveDialog>>;

const renderDialog = (props: DialogProps = {}) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <ResponsiveDialog open {...props}>
      <DialogContent>Pick a subject</DialogContent>
    </ResponsiveDialog>
  </ThemeProvider>
);

// MUI reads the breakpoint through matchMedia, which jsdom does not implement; without a stand-in
// useMediaQuery just reports false, so the full-screen branch needs one.
const stubMatchMedia = (matches: boolean) => vi.stubGlobal("matchMedia", (query: string) => ({
  matches,
  media: query,
  onchange: null,
  addListener: () => { /* deprecated, unused */ },
  removeListener: () => { /* deprecated, unused */ },
  addEventListener: () => { /* no live changes in these tests */ },
  removeEventListener: () => { /* no live changes in these tests */ },
  dispatchEvent: () => false,
}));

describe("ResponsiveDialog", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows its contents", () => {
    renderDialog();

    expect(screen.getByText("Pick a subject")).toBeInTheDocument();
  });

  it("has no title bar unless given a title", () => {
    renderDialog();

    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
  });

  it("shows the title it is given", () => {
    renderDialog({ title: "Select a subject" });

    expect(screen.getByRole("heading", { name: "Select a subject" })).toBeInTheDocument();
  });

  it("offers a close button only when asked", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderDialog({ title: "Select a subject", withCloseButton: true, onClose });

    await user.click(screen.getByRole("button", { name: "close" }));

    expect(onClose).toHaveBeenCalled();
  });

  it("has no close button by default", () => {
    renderDialog({ title: "Select a subject" });

    expect(screen.queryByRole("button", { name: "close" })).not.toBeInTheDocument();
  });

  it("closes on escape", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderDialog({ onClose });

    await user.keyboard("{Escape}");

    expect(onClose).toHaveBeenCalled();
  });

  it("disables the close button while closing is refused", () => {
    renderDialog({ title: "Select a subject", withCloseButton: true, closeDisabled: true, onClose: vi.fn() });

    expect(screen.getByRole("button", { name: "close" })).toBeDisabled();
  });

  it("ignores escape while closing is refused", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderDialog({ closeDisabled: true, onClose });

    await user.keyboard("{Escape}");

    expect(onClose).not.toHaveBeenCalled();
  });

  it("ignores a click on the backdrop, so work in progress is not lost", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderDialog({ onClose });

    await user.click(document.querySelector(".MuiBackdrop-root")!);

    expect(onClose).not.toHaveBeenCalled();
  });

  it("tolerates being closed with nobody listening", async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.keyboard("{Escape}");

    expect(screen.getByText("Pick a subject")).toBeInTheDocument();
  });

  it("goes full screen once the viewport is narrower than its width", () => {
    stubMatchMedia(true);

    renderDialog();

    expect(screen.getByRole("dialog")).toHaveClass("MuiDialog-paperFullScreen");
  });

  it("stays a windowed dialog on a wide viewport", () => {
    stubMatchMedia(false);

    renderDialog();

    expect(screen.getByRole("dialog")).not.toHaveClass("MuiDialog-paperFullScreen");
  });

  it("forwards a ref to the dialog", () => {
    const ref = { current: null as HTMLDivElement | null };

    renderDialog({ ref });

    expect(ref.current).toBeInstanceOf(HTMLElement);
  });
});
