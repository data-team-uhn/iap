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

import UserMenu from "@iap/homepage/UserMenu";

import { renderWithMessages as render } from "./messages.fixture";

// Answers the two Sling endpoints the menu consults: the session info (who is logged in) and
// the user's properties (their full name).
const stubUserEndpoints = (userId: string, userProperties: Record<string, unknown> = {}) =>
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve({
    ok: true,
    json: () => Promise.resolve(
      url.endsWith("sessionInfo.json") ? { userID: userId } : userProperties),
  } as unknown as Response)));

afterEach(() => vi.unstubAllGlobals());

describe("UserMenu", () => {
  it("shows an avatar with the user's initials, from their full name", async () => {
    stubUserEndpoints("jdoe", { displayName: "Jane Doe" });

    render(<UserMenu />);

    // The initials render only after the two chained fetches resolve; a coverage-instrumented
    // run on a loaded machine can outlast the default 1s waiting window, so allow extra room.
    expect(await screen.findByText("JD", {}, { timeout: 5000 })).toBeInTheDocument();
  });

  it("falls back to the user name for the initials when there is no full name", async () => {
    stubUserEndpoints("admin");

    render(<UserMenu />);

    expect(await screen.findByText("A")).toBeInTheDocument();
  });

  it("identifies the account and offers a working sign out in its menu", async () => {
    stubUserEndpoints("jdoe", { displayName: "Jane Doe" });

    render(<UserMenu />);

    fireEvent.click(await screen.findByRole("button", { name: "Your account" }));

    expect(await screen.findByText("jdoe")).toBeInTheDocument();
    expect(screen.getByText("Jane Doe")).toBeInTheDocument();
    const signOut = screen.getByText("Sign out").closest("a");
    expect(signOut).toHaveAttribute("href", "/system/sling/logout");
  });

  it("still says whose account it is, without putting the name in the button's own label", async () => {
    // The label is a translated phrase and the account holder's name is not translatable, and there is no
    // message formatter in the browser to combine the two without a translator having to accept English
    // word order. So the name moved to the tooltip, which a screen reader reads as the button's
    // description — this is the test that keeps it reachable rather than merely intended.
    stubUserEndpoints("jdoe", { displayName: "Jane Doe" });

    render(<UserMenu />);
    fireEvent.mouseOver(await screen.findByRole("button", { name: "Your account" }));

    expect(await screen.findByRole("tooltip")).toHaveTextContent("jdoe");
  });

  it("renders nothing while the user is unknown", () => {
    // A fetch that never resolves keeps the user unidentified
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => undefined)));

    const { container } = render(<UserMenu />);

    expect(container).toBeEmptyDOMElement();
  });

  it("closes its menu again", async () => {
    stubUserEndpoints("jdoe", { displayName: "Jane Doe" });
    render(<UserMenu />);
    fireEvent.click(await screen.findByRole("button", { name: "Your account" }));
    const entry = await screen.findByText("Sign out");

    fireEvent.keyDown(entry, { key: "Escape", code: "Escape" });

    await waitFor(() => { expect(screen.queryByText("Sign out")).not.toBeInTheDocument(); });
  });

  it("renders nothing when the session reports no user at all", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({
      ok: true,
      json: () => Promise.resolve({}),
    } as unknown as Response)));

    const { container } = render(<UserMenu />);

    await waitFor(() => { expect(container).toBeEmptyDOMElement(); });
  });

  it("shows no initials for a user whose name is all whitespace", async () => {
    stubUserEndpoints("   ", { displayName: "   " });

    const { container } = render(<UserMenu />);

    // A blank user name leaves the menu unrendered altogether
    await waitFor(() => { expect(container).toBeEmptyDOMElement(); });
  });

  it("reports a failure to identify the user, and renders nothing", async () => {
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => { /* keep the output quiet */ });
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({ ok: false, status: 403 } as Response)));

    const { container } = render(<UserMenu />);

    await waitFor(() => {
      expect(errorSpy).toHaveBeenCalledWith("Something went wrong identifying the current user", expect.any(Error));
    });
    expect(container).toBeEmptyDOMElement();
    errorSpy.mockRestore();
  });

  it("falls back to the user name when the profile cannot be read", async () => {
    vi.stubGlobal("fetch", vi.fn((url: RequestInfo | URL) => Promise.resolve(
      String(url).endsWith("sessionInfo.json")
        ? { ok: true, json: () => Promise.resolve({ userID: "jdoe" }) }
        : { ok: false, status: 500 }
    ) as unknown as Promise<Response>));

    render(<UserMenu />);

    // The initials still come from the user name, and the menu offers no full name
    expect(await screen.findByText("J")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Your account" }));
    expect(await screen.findByText("jdoe")).toBeInTheDocument();
  });
});
