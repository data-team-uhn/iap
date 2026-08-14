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

import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router";

import AdminConsoleButton from "@iap/admin-console/AdminConsoleButton";

// Exposes the router's current location, so the test can observe navigation.
function LocationProbe() {
  return <span data-testid="location">{useLocation().pathname}</span>;
}

describe("AdminConsoleButton", () => {
  it("navigates to the administration console when clicked", () => {
    render(
      <MemoryRouter initialEntries={["/"]}>
        <AdminConsoleButton />
        <LocationProbe />
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole("button", { name: "Administration" }));

    expect(screen.getByTestId("location")).toHaveTextContent("/admin");
  });
});
