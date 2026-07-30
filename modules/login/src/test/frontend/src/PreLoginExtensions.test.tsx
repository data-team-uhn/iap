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

import PreLoginExtensions from "@iap/login/PreLoginExtensions";
import { type Extension } from "@iap/ui-extension/ExtensionList";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(),
}));

const mockedLoadExtensions = vi.mocked(loadExtensions);

// A frame-top extension as returned by loadExtensions, with the render asset already resolved
const notice = (name: string, visibleBeforeLogin?: unknown): Extension => ({
  "iap:extensionName": name,
  ...(visibleBeforeLogin === undefined ? {} : { "iap:visibleBeforeLogin": visibleBeforeLogin }),
  "iap:extensionRender": () => <div>{`${name} content`}</div>,
});

describe("PreLoginExtensions", () => {
  it("renders only the frame-top extensions opting into pre-login visibility", async () => {
    mockedLoadExtensions.mockResolvedValue([
      notice("Maintenance", true),
      notice("AppBar", false),
      notice("Unflagged"),
    ]);

    render(<PreLoginExtensions />);

    expect(await screen.findByText("Maintenance content")).toBeInTheDocument();
    expect(screen.queryByText("AppBar content")).not.toBeInTheDocument();
    // Visibility is opt-in: an extension that doesn't declare the flag stays post-login only
    expect(screen.queryByText("Unflagged content")).not.toBeInTheDocument();
    expect(mockedLoadExtensions).toHaveBeenCalledWith("FrameTop");
  });

  it("tolerates a failure to load the extensions", async () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);
    mockedLoadExtensions.mockRejectedValue(new Error("network error"));

    const { container } = render(<PreLoginExtensions />);

    await waitFor(() => expect(consoleError).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
    consoleError.mockRestore();
  });
});
