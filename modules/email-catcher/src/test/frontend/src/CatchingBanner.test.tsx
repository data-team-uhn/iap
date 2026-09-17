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
import { act, render, screen, waitFor } from "@testing-library/react";

import CatchingBanner from "@iap/email-catcher/CatchingBanner";
import { appTheme } from "@iap/frontend-commons/appTheme";

const answering = (body: unknown, status = 200) =>
  vi.fn((_url: string) => Promise.resolve(new Response(JSON.stringify(body),
    { status, headers: { "Content-Type": "application/json" } })));

const banner = () => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <CatchingBanner />
  </ThemeProvider>
);

const WARNING = /No email is being sent from this instance/;

describe("CatchingBanner", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("warns on every page while mail is being caught", async () => {
    const fetchMock = answering({ catching: true });
    vi.stubGlobal("fetch", fetchMock);
    banner();

    expect(await screen.findByText(WARNING)).toBeInTheDocument();
    expect(fetchMock.mock.calls[0][0]).toBe("/libs/iap/mail-catcher.catching.json");
  });

  it("says nothing at all while mail is being delivered", async () => {
    vi.stubGlobal("fetch", answering({ catching: false }));
    const { container } = banner();

    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it("asks without credentials, because it runs before there is a session", async () => {
    // useAuthenticatedFetch reads a failure as an expired session and offers to sign in again,
    // which is the wrong answer to a question anybody may ask — including somebody waiting for the
    // password-reset mail this banner exists to warn them about.
    const fetchMock = answering({ catching: true });
    vi.stubGlobal("fetch", fetchMock);
    banner();

    await screen.findByText(WARNING);
    expect(fetchMock.mock.calls[0]).toHaveLength(1);
  });

  it("stays silent when the endpoint cannot be reached", async () => {
    // A warning nobody could fetch is the same as no warning; an error across every page because
    // one endpoint was down would be worse than the thing it reports.
    vi.stubGlobal("fetch",
      vi.fn((_url: string) => Promise.reject(new TypeError("Failed to fetch"))));
    const { container } = banner();

    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it("stays silent when the endpoint refuses", async () => {
    vi.stubGlobal("fetch", answering({}, 403));
    const { container } = banner();

    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it("stays silent when the answer is not the shape it expects", async () => {
    vi.stubGlobal("fetch", answering({ catching: "yes" }));
    const { container } = banner();

    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it("asks again when catching is switched in this tab", async () => {
    // Without this the administrator who just turned catching on watches nothing happen on the very
    // page that is supposed to warn them, until they navigate.
    let catching = false;
    const fetchMock = vi.fn((_url: string) =>
      Promise.resolve(new Response(JSON.stringify({ catching }),
        { status: 200, headers: { "Content-Type": "application/json" } })));
    vi.stubGlobal("fetch", fetchMock);
    const { container } = banner();

    await waitFor(() => expect(container).toBeEmptyDOMElement());

    catching = true;
    act(() => {
      // Braces, not a bare arrow: dispatchEvent returns a boolean, which makes this the async
      // overload of act and leaves a floating promise.
      window.dispatchEvent(new Event("iap:mail-catching-changed"));
    });

    expect(await screen.findByText(WARNING)).toBeInTheDocument();
  });

  it("stops listening once it is gone", async () => {
    const fetchMock = answering({ catching: false });
    vi.stubGlobal("fetch", fetchMock);
    const { unmount } = banner();

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    unmount();
    window.dispatchEvent(new Event("iap:mail-catching-changed"));

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
