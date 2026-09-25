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

import type { ReactElement } from "react";

import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";

import AdminScreen from "@iap/admin-console/AdminScreen";
import { appTheme } from "@iap/frontend-commons/appTheme";

// The title's semantic element comes from the app theme's `pageTitle` variant mapping, so the
// screen needs the real theme around it.
const renderScreen = (ui: ReactElement) => render(<ThemeProvider theme={appTheme}>{ui}</ThemeProvider>);

describe("AdminScreen", () => {
  it("titles the landing page itself when no tool title is given", () => {
    renderScreen(<AdminScreen>content</AdminScreen>);

    expect(screen.getByRole("heading", { level: 1, name: "Administration" })).toBeInTheDocument();
    expect(screen.getByText("content")).toBeInTheDocument();
  });

  it("titles a tool page with its name, without any breadcrumb chrome", () => {
    renderScreen(<AdminScreen title="Submission categories">tool content</AdminScreen>);

    expect(screen.getByRole("heading", { level: 1, name: "Submission categories" })).toBeInTheDocument();
    expect(screen.getByText("tool content")).toBeInTheDocument();
    // Wayfinding is left to the shell, so the screen itself renders no links back to the console
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("flags a tool's page as administrative beside its title, but not the landing page", () => {
    const { unmount } = renderScreen(<AdminScreen title="Some tool">content</AdminScreen>);
    expect(screen.getByText("Admin")).toBeInTheDocument();
    unmount();

    renderScreen(<AdminScreen>content</AdminScreen>);
    expect(screen.queryByText("Admin")).not.toBeInTheDocument();
  });

  it("describes the tool under its title, outside the panel", () => {
    renderScreen(<AdminScreen title="Some tool" description="What it is for"><span>content</span></AdminScreen>);

    expect(screen.getByText("What it is for").closest(".MuiPaper-root")).toBeNull();
    expect(screen.getByText("content").closest(".MuiPaper-root")).not.toBeNull();
  });

  it("sets the content on a panel", () => {
    renderScreen(<AdminScreen title="Some tool"><span>content</span></AdminScreen>);

    expect(screen.getByText("content").closest(".MuiPaper-root")).not.toBeNull();
  });

  it("lays the content directly on the page when the panel is disabled", () => {
    renderScreen(<AdminScreen title="Some tool" disablePanel><span>content</span></AdminScreen>);

    expect(screen.getByText("content").closest(".MuiPaper-root")).toBeNull();
  });

  it("displays the main action on the heading's row", () => {
    renderScreen(<AdminScreen title="Some tool" action={<button>New thing</button>}>content</AdminScreen>);

    const row = screen.getByRole("heading", { name: "Some tool" }).parentElement?.parentElement;
    expect(row).toContainElement(screen.getByRole("button", { name: "New thing" }));
    expect(row).not.toContainElement(screen.getByText("content"));
  });
});
