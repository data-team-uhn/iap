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
import { MemoryRouter } from "react-router";

import AdminScreen from "@iap/admin-console/AdminScreen";
import { appTheme } from "@iap/frontend-commons/appTheme";

// The screen reads the current URL to decide its top spacing, so it needs a router around it; the
// title's semantic element comes from the app theme's `pageTitle` variant mapping, so it needs
// the real theme too.
const renderAt = (url: string, ui: ReactElement) =>
  render(
    <ThemeProvider theme={appTheme}>
      <MemoryRouter initialEntries={[url]}>{ui}</MemoryRouter>
    </ThemeProvider>
  );

describe("AdminScreen", () => {
  it("titles the landing page itself when no tool title is given", () => {
    renderAt("/admin", <AdminScreen>content</AdminScreen>);

    expect(screen.getByRole("heading", { name: "Administration" })).toBeInTheDocument();
    expect(screen.getByText("content")).toBeInTheDocument();
  });

  it("titles a tool page with its name, without any breadcrumb chrome", () => {
    renderAt("/admin/categories", <AdminScreen title="Submission categories">tool content</AdminScreen>);

    expect(screen.getByRole("heading", { name: "Submission categories" })).toBeInTheDocument();
    expect(screen.getByText("tool content")).toBeInTheDocument();
    // Wayfinding is left to the shell, so the screen itself renders no links back to the console
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("displays the main action next to the heading", () => {
    renderAt("/admin/categories", <AdminScreen title="Some tool" action={<button>New thing</button>} />);

    expect(screen.getByRole("button", { name: "New thing" })).toBeInTheDocument();
  });
});
