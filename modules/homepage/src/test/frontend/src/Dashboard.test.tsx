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

import { render, screen } from "@testing-library/react";

import Dashboard from "@iap/homepage/Dashboard";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

// Only the loading half is mocked; visibleInPersona is pure, and the persona filtering the shared
// WidgetDashboard applies is only worth exercising against the real predicate.
vi.mock("@iap/ui-extension/extensionManager", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@iap/ui-extension/extensionManager")>()),
  loadExtensions: vi.fn(),
}));

const mockedLoadExtensions = vi.mocked(loadExtensions);

// The layout itself (grid, frames, loading, empty state) is covered by the shared
// WidgetDashboard's own tests in frontend-commons; here only the binding matters.
describe("Dashboard", () => {
  it("lays out the widgets of the iap/dashboard/widget extension point", async () => {
    mockedLoadExtensions.mockResolvedValue([{
      "iap:extensionName": "Welcome",
      "iap:extensionRender": () => <div>Welcome content</div>,
    }]);

    render(<Dashboard />);

    expect(await screen.findByText("Welcome content")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Welcome" })).toBeInTheDocument();
    expect(mockedLoadExtensions).toHaveBeenCalledWith("DashboardWidget");
  });
});
