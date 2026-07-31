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
import userEvent from "@testing-library/user-event";

import { appTheme } from "@iap/frontend-commons/appTheme";
import ErrorPage from "@iap/frontend-commons/components/ErrorPage";

import type { ComponentProps } from "react";

const renderErrorPage = (props: Partial<ComponentProps<typeof ErrorPage>> = {}) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <ErrorPage {...props} />
  </ThemeProvider>
);

describe("ErrorPage", () => {
  it("shows the code, title and message it is given", () => {
    renderErrorPage({ errorCode: "404", title: "Not found", message: "No such page" });

    expect(screen.getByRole("heading", { level: 1, name: "404" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Not found" })).toBeInTheDocument();
    expect(screen.getByText("No such page")).toBeInTheDocument();
  });

  it("renders bare when given nothing to say", () => {
    const { container } = renderErrorPage();

    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    // Only the (unconfigured, so empty) logo slot is left
    expect(container.textContent).toBe("");
  });

  it("offers the action button only when it has a label", async () => {
    const user = userEvent.setup();
    renderErrorPage({ buttonLabel: "Go home", buttonLink: "/home" });

    const original = window.location.href;
    const setHref = vi.fn();
    Object.defineProperty(window, "location", {
      configurable: true,
      value: { ...window.location, get href() { return original; }, set href(value: string) { setHref(value); } },
    });

    await user.click(screen.getByRole("button", { name: "Go home" }));

    expect(setHref).toHaveBeenCalledWith("/home");
  });

  it("navigates nowhere in particular when the button has no link", async () => {
    const user = userEvent.setup();
    renderErrorPage({ buttonLabel: "Go home" });

    const setHref = vi.fn();
    Object.defineProperty(window, "location", {
      configurable: true,
      value: { ...window.location, set href(value: string) { setHref(value); } },
    });

    await user.click(screen.getByRole("button", { name: "Go home" }));

    expect(setHref).toHaveBeenCalledWith("");
  });

  it("hides the button when only a link is supplied", () => {
    renderErrorPage({ buttonLink: "/home" });

    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  // An explicitly empty colour has to fall back to the default just as a missing one does
  it.each(["", undefined])("falls back to the default colours for %o", colour => {
    renderErrorPage({
      errorCode: "500",
      title: "Boom",
      message: "Something broke",
      errorCodeColor: colour,
      titleColor: colour,
      messageColor: colour,
    });

    expect(screen.getByRole("heading", { level: 1, name: "500" })).toBeInTheDocument();
  });

  it("accepts explicit colours", () => {
    renderErrorPage({ errorCode: "500", title: "Boom", message: "Broke", errorCodeColor: "error" });

    expect(screen.getByRole("heading", { level: 1, name: "500" })).toBeInTheDocument();
  });
});
