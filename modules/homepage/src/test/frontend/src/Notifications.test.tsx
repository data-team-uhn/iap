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

import { fireEvent, screen, waitFor } from "@testing-library/react";

import Notifications from "@iap/homepage/Notifications";

import { renderWithMessages as render } from "./messages.fixture";

describe("Notifications", () => {
  it("reveals the (currently empty) notifications dropdown when the bell is clicked", async () => {
    render(<Notifications />);

    expect(screen.queryByText("You have no new notifications")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Notifications" }));

    expect(await screen.findByText("You have no new notifications")).toBeInTheDocument();
  });

  it("closes the dropdown again", async () => {
    render(<Notifications />);
    fireEvent.click(screen.getByRole("button", { name: "Notifications" }));
    const entry = await screen.findByText("You have no new notifications");

    fireEvent.keyDown(entry, { key: "Escape", code: "Escape" });

    await waitFor(() => {
      expect(screen.queryByText("You have no new notifications")).not.toBeInTheDocument();
    });
  });
});
