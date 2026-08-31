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

import CaughtMailWidget from "@iap/email-catcher/CaughtMailWidget";
import { appTheme } from "@iap/frontend-commons/appTheme";

const answering = (enabled: boolean, total: number) =>
  vi.fn((_url: string) => Promise.resolve(new Response(JSON.stringify({ enabled, total }),
    { status: 200, headers: { "Content-Type": "application/json" } })));

// The widget renders only the summary; the way through to the list is the dashboard frame's own
// header action, declared on the extension rather than drawn here.
const widget = () => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <CaughtMailWidget />
  </ThemeProvider>
);

describe("CaughtMailWidget", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("says mail is being caught, and how much of it", async () => {
    const fetchMock = answering(true, 12);
    vi.stubGlobal("fetch", fetchMock);
    widget();

    expect(await screen.findByText("12")).toBeInTheDocument();
    expect(screen.getByText("On")).toBeInTheDocument();
    expect(screen.getByText("Caught so far")).toBeInTheDocument();
    expect(fetchMock.mock.calls[0][0]).toBe("/CaughtMail.status.json");
  });

  it("says mail is being delivered normally, so that an empty list is not read as no mail", async () => {
    // "Off with nothing caught" and "on with nothing caught" look identical in a count and mean
    // opposite things: one is a working instance, the other is a notification that never fired
    vi.stubGlobal("fetch", answering(false, 0));
    widget();

    expect(await screen.findByText("Off")).toBeInTheDocument();
    expect(screen.getByText("Mail is being delivered normally, so nothing new will appear here."))
      .toBeInTheDocument();
  });

  it("accounts for messages caught before the catcher was switched off", async () => {
    vi.stubGlobal("fetch", answering(false, 4));
    widget();

    expect(await screen.findByText("Mail is being delivered normally now; these were caught earlier."))
      .toBeInTheDocument();
    expect(screen.getByText("4")).toBeInTheDocument();
  });

  it("says nothing has been sent yet rather than leaving a bare zero", async () => {
    vi.stubGlobal("fetch", answering(true, 0));
    widget();

    expect(await screen.findByText("Nothing has been sent yet.")).toBeInTheDocument();
  });

  it("does not explain an empty mailbox on an instance that is catching and has caught", async () => {
    vi.stubGlobal("fetch", answering(true, 2));
    widget();

    await screen.findByText("2");
    expect(screen.queryByText("Nothing has been sent yet.")).not.toBeInTheDocument();
    expect(screen.queryByText(/delivered normally/)).not.toBeInTheDocument();
  });

  it("says the caught mail is unavailable rather than showing an Off it cannot vouch for", async () => {
    // Reaching the administration console is not the same as being allowed to read /CaughtMail,
    // and "Off" would be a claim rather than an absence of one
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response("", { status: 403 }))));
    widget();

    expect(await screen.findByText("The caught mail is not available to you.")).toBeInTheDocument();
    expect(screen.queryByText("Caught so far")).not.toBeInTheDocument();
  });

  it("shows a placeholder until the answer arrives", () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => { /* never settles */ })));
    widget();

    expect(screen.getByLabelText("Loading the caught mail summary")).toBeInTheDocument();
  });

  it("does nothing with an answer that arrives after it has gone", async () => {
    // The dashboard re-lays itself out when the persona changes, so a widget can leave while its
    // own request is still in flight
    const answers: ((response: Response) => void)[] = [];
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(resolve => { answers.push(resolve); })));
    const { unmount } = widget();
    await waitFor(() => { expect(answers).toHaveLength(1); });

    unmount();
    await act(async () => {
      answers.forEach(answer => {
        answer(new Response(JSON.stringify({ enabled: true, total: 1 }),
          { status: 200, headers: { "Content-Type": "application/json" } }));
      });
      // One microtask is not enough: the answer is read through an awaited json() call
      await new Promise(resolve => { setTimeout(resolve, 0); });
    });

    expect(screen.queryByText("Caught so far")).not.toBeInTheDocument();
  });
});
