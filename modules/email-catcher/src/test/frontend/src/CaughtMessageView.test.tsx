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

import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useNavigate } from "react-router";

import CaughtMessageView from "@iap/email-catcher/CaughtMessageView";

const STORED = {
  "@path": "/CaughtMail/abc",
  "@name": "abc",
  "subject": "Your proposal has been approved",
  "caughtAt": "2026-08-20T18:30:00.000+00:00",
  "from": [ "IAP <iap@uhn.ca>" ],
  "to": [ "Someone <someone@uhn.ca>", "other@uhn.ca" ],
  "cc": [ "reb@uhn.ca" ],
  "headers": [ "Message-ID: <1@uhn.ca>" ],
  "textBody": "Approved.",
  "htmlBody": "<p>Approved, <b>congratulations</b>.</p>",
};

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body),
  { status, headers: { "Content-Type": "application/json" } });

const answering = (body: unknown, status = 200) =>
  vi.fn((_url: string) => Promise.resolve(json(body, status)));

const view = (route = "/admin/mail/abc") => render(
  <MemoryRouter initialEntries={[ route ]}>
    <CaughtMessageView />
  </MemoryRouter>
);

// Reads one message and then another: the page stays mounted and only the route changes.
function Reader() {
  const navigate = useNavigate();
  return (
    <>
      <button type="button" onClick={() => { void navigate("/admin/mail/def"); }}>Next</button>
      <CaughtMessageView />
    </>
  );
}

const reader = () => render(
  <MemoryRouter initialEntries={[ "/admin/mail/abc" ]}>
    <Reader />
  </MemoryRouter>
);

describe("CaughtMessageView", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("reads the message the address names, and heads the page with its subject", async () => {
    const fetchMock = answering(STORED);
    vi.stubGlobal("fetch", fetchMock);
    view();

    expect(await screen.findByText("Your proposal has been approved")).toBeInTheDocument();
    expect(fetchMock.mock.calls[0][0]).toBe("/CaughtMail/abc.json");
  });

  it("shows every address list the message carried, one address per line", async () => {
    vi.stubGlobal("fetch", answering(STORED));
    view();

    expect(await screen.findByText("Someone <someone@uhn.ca>")).toBeInTheDocument();
    expect(screen.getByText("other@uhn.ca")).toBeInTheDocument();
    expect(screen.getByText("IAP <iap@uhn.ca>")).toBeInTheDocument();
    expect(screen.getByText("Cc")).toBeInTheDocument();
  });

  it("shows no heading for an address list the message did not carry", async () => {
    vi.stubGlobal("fetch", answering(STORED));
    view();

    await screen.findByText("Cc");
    expect(screen.queryByText("Bcc")).not.toBeInTheDocument();
    expect(screen.queryByText("Reply to")).not.toBeInTheDocument();
  });

  it("draws the HTML body in a sandbox with nothing allowed", async () => {
    vi.stubGlobal("fetch", answering(STORED));
    view();

    const frame = await screen.findByTitle("The message as a recipient would see it");
    expect(frame).toHaveAttribute("sandbox", "");
    expect(frame).toHaveAttribute("srcdoc", "<p>Approved, <b>congratulations</b>.</p>");
  });

  it("offers the plain text and the HTML source beside the rendering", async () => {
    vi.stubGlobal("fetch", answering(STORED));
    view();

    await userEvent.click(await screen.findByRole("tab", { name: "Plain text" }));
    expect(screen.getByText("Approved.")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("tab", { name: "HTML source" }));
    expect(screen.getByText("<p>Approved, <b>congratulations</b>.</p>")).toBeInTheDocument();
  });

  it("offers no choice for a message with only one body", async () => {
    vi.stubGlobal("fetch", answering({ textBody: "Approved." }));
    view();

    expect(await screen.findByText("Approved.")).toBeInTheDocument();
    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
  });

  it("falls back to a body the message actually has", async () => {
    vi.stubGlobal("fetch", answering({ textBody: "Only text." }));
    view();

    expect(await screen.findByText("Only text.")).toBeInTheDocument();
  });

  it("says a message has no body rather than showing an empty panel", async () => {
    vi.stubGlobal("fetch", answering({ subject: "Empty" }));
    view();

    expect(await screen.findByText("This message has no body.")).toBeInTheDocument();
  });

  it("shows the remaining headers, and nothing when there are none", async () => {
    vi.stubGlobal("fetch", answering(STORED));
    const { unmount } = view();
    expect(await screen.findByText("Headers")).toBeInTheDocument();
    expect(screen.getByText("Message-ID: <1@uhn.ca>")).toBeInTheDocument();
    unmount();

    vi.stubGlobal("fetch", answering({ textBody: "Approved." }));
    view();
    await screen.findByText("Approved.");
    expect(screen.queryByText("Headers")).not.toBeInTheDocument();
  });

  it("shows the raw timestamp when it is not one it can parse", async () => {
    vi.stubGlobal("fetch", answering({ caughtAt: "not a date", textBody: "x" }));
    view();

    expect(await screen.findByText("not a date")).toBeInTheDocument();
  });

  it("heads the page with something when the message carries no subject", async () => {
    vi.stubGlobal("fetch", answering({ textBody: "x" }));
    view();

    expect(await screen.findByText("Caught message")).toBeInTheDocument();
    expect(screen.getByText("—")).toBeInTheDocument();
  });

  it("says an address naming no message is not one, rather than failing to load", async () => {
    const fetchMock = answering(STORED);
    vi.stubGlobal("fetch", fetchMock);
    view("/admin/mail");

    expect(await screen.findByText("This address does not name a caught message.")).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("reports a message it could not read, and reads it again when asked", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response("", { status: 404 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(STORED),
        { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    view();

    expect(await screen.findByText("The message could not be read")).toBeInTheDocument();
    expect(screen.getByText(/could not be found on the server/)).toBeInTheDocument();
    expect(screen.getByText(/\(HTTP 404\)/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Retry" }));

    await waitFor(() => {
      expect(screen.getByTitle("The message as a recipient would see it")).toBeInTheDocument();
    });
  });

  it("does not leave one message on screen under the next one's heading", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve(json(
      url.startsWith("/CaughtMail/abc") ? STORED : { subject: "The second one", textBody: "Later." }))));
    reader();
    await screen.findByText("Your proposal has been approved");

    await userEvent.click(screen.getByRole("button", { name: "Next" }));

    expect(await screen.findByText("The second one")).toBeInTheDocument();
    expect(screen.queryByText("Your proposal has been approved")).not.toBeInTheDocument();
    expect(screen.queryByText("Message-ID: <1@uhn.ca>")).not.toBeInTheDocument();
  });

  it("ignores a read that lands after the address has moved on", async () => {
    const pending: ((response: Response) => void)[] = [];
    vi.stubGlobal("fetch", vi.fn((url: string) => (url.startsWith("/CaughtMail/abc")
      ? new Promise<Response>(resolve => { pending.push(resolve); })
      : Promise.resolve(json({ subject: "The second one", textBody: "Later." })))));
    reader();
    await waitFor(() => { expect(pending).toHaveLength(1); });

    await userEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findByText("The second one")).toBeInTheDocument();

    await act(async () => {
      pending[0](json(STORED));
      await new Promise(resolve => { setTimeout(resolve, 0); });
    });

    expect(screen.queryByText("Your proposal has been approved")).not.toBeInTheDocument();
    expect(screen.getByText("The second one")).toBeInTheDocument();
  });

  it("reports a request that never reached the server", async () => {
    // fetch rejects with a TypeError when the request never reaches the server
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new TypeError("Failed to fetch"))));
    view();

    expect(await screen.findByText(/The server could not be reached/)).toBeInTheDocument();
  });
});
