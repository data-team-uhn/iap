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
import { MemoryRouter } from "react-router";

import AdminDashboard from "@iap/admin-console/AdminDashboard";
import { appTheme } from "@iap/frontend-commons/appTheme";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(),
}));

const mockedLoadExtensions = vi.mocked(loadExtensions);

// AdminScreen reads the current URL (router) and titles itself through the app theme's
// `pageTitle` variant mapping (theme), so the console needs both around it.
const renderDashboard = () => render(
  <ThemeProvider theme={appTheme}>
    <MemoryRouter initialEntries={["/admin"]}>
      <AdminDashboard />
    </MemoryRouter>
  </ThemeProvider>
);

describe("AdminDashboard", () => {
  it("lays out each administrative tool as a titled widget with a subtitle", async () => {
    mockedLoadExtensions.mockResolvedValue([{
      "iap:extensionName": "Submission categories",
      "iap:subtitle": "Organize the categories submitters choose from, and bind them to schemas",
      "iap:extensionRender": () => <div>Category summary</div>,
    }]);

    renderDashboard();

    expect(await screen.findByText("Category summary")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Submission categories" })).toBeInTheDocument();
    expect(screen.getByText("Organize the categories submitters choose from, and bind them to schemas"))
      .toBeInTheDocument();
    expect(mockedLoadExtensions).toHaveBeenCalledWith("AdminDashboard");
    // The console's own heading is still there, around the widgets
    expect(screen.getByRole("heading", { name: "Administration" })).toBeInTheDocument();
  });

  it("renders a friendly empty state when no tools are available", async () => {
    mockedLoadExtensions.mockResolvedValue([]);

    renderDashboard();

    expect(await screen.findByText("No administration tools are available.")).toBeInTheDocument();
  });

  it("renders the empty state, not a crash, when the extension point fails to load", async () => {
    mockedLoadExtensions.mockRejectedValue(new Error("network down"));

    renderDashboard();

    expect(await screen.findByText("No administration tools are available.")).toBeInTheDocument();
  });
});
