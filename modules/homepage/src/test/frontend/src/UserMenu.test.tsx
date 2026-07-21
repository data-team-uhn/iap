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

import UserMenu from "@iap/homepage/UserMenu";

// Answers the two Sling endpoints the menu consults: the session info (who is logged in) and
// the user's properties (their full name).
const stubUserEndpoints = (userId: string, userProperties: Record<string, unknown> = {}) =>
  vi.stubGlobal("fetch", vi.fn((url: RequestInfo | URL) => Promise.resolve({
    ok: true,
    json: () => Promise.resolve(
      String(url).endsWith("sessionInfo.json") ? { userID: userId } : userProperties),
  } as unknown as Response)));

afterEach(() => vi.unstubAllGlobals());

describe("UserMenu", () => {
  it("shows an avatar with the user's initials, from their full name", async () => {
    stubUserEndpoints("jdoe", { displayName: "Jane Doe" });

    render(<UserMenu />);

    expect(await screen.findByText("JD")).toBeInTheDocument();
  });

  it("falls back to the user name for the initials when there is no full name", async () => {
    stubUserEndpoints("admin");

    render(<UserMenu />);

    expect(await screen.findByText("A")).toBeInTheDocument();
  });

  it("identifies the account and offers a working sign out in its menu", async () => {
    stubUserEndpoints("jdoe", { displayName: "Jane Doe" });

    render(<UserMenu />);

    fireEvent.click(await screen.findByRole("button", { name: "Account: jdoe" }));

    expect(await screen.findByText("jdoe")).toBeInTheDocument();
    expect(screen.getByText("Jane Doe")).toBeInTheDocument();
    const signOut = screen.getByText("Sign out").closest("a");
    expect(signOut).toHaveAttribute("href", "/system/sling/logout");
  });

  it("renders nothing while the user is unknown", () => {
    // A fetch that never resolves keeps the user unidentified
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => {})));

    const { container } = render(<UserMenu />);

    expect(container).toBeEmptyDOMElement();
  });
});
