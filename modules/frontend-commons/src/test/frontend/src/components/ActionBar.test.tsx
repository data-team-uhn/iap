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

import { render, screen, waitFor } from "@testing-library/react";

import { getActions } from "@iap/frontend-commons/actionsManager";
import ActionBar from "@iap/frontend-commons/components/ActionBar";

vi.mock("@iap/frontend-commons/actionsManager", () => ({ getActions: vi.fn() }));

const mockedGetActions = vi.mocked(getActions);

const Approve = ({ subject }: Record<string, unknown>) => <button type="button">Approve {String(subject)}</button>;

afterEach(() => {
  vi.restoreAllMocks();
});

describe("ActionBar", () => {
  it("renders the point's actions in this place, each given the other props", async () => {
    mockedGetActions.mockResolvedValue([Approve]);

    render(<ActionBar point="ThingActions" place="page" subject="Consent" />);

    expect(await screen.findByRole("button", { name: "Approve Consent" })).toBeInTheDocument();
    expect(mockedGetActions).toHaveBeenCalledWith("ThingActions", "page");
  });

  it("reports an extension point it cannot read, and shows no action", async () => {
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => { /* keep the output quiet */ });
    const failure = new Error("Failed to load extensions from ThingActions: 500");
    mockedGetActions.mockRejectedValue(failure);

    render(<ActionBar point="ThingActions" />);

    await waitFor(() => {
      expect(errorSpy).toHaveBeenCalledWith("Something went wrong loading the ThingActions actions", failure);
    });
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
