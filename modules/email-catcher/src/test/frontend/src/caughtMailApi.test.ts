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

import {
  fetchCatcherStatus,
  fetchCaughtMessage,
  messageNameFromRoute,
  messageRoute,
} from "@iap/email-catcher/caughtMailApi";

const answering = (body: unknown, status = 200) =>
  vi.fn(() => Promise.resolve(new Response(JSON.stringify(body),
    { status, headers: { "Content-Type": "application/json" } })));

describe("messageRoute and messageNameFromRoute", () => {
  it("round-trip a message name", () => {
    expect(messageNameFromRoute(messageRoute("abc"))).toBe("abc");
  });

  it("names nothing on the list page itself", () => {
    expect(messageNameFromRoute("/admin/mail")).toBeNull();
    // A trailing slash is the same page, not a message with an empty name
    expect(messageNameFromRoute("/admin/mail/")).toBeNull();
  });

  it("names nothing under an unrelated route", () => {
    expect(messageNameFromRoute("/admin/errors/abc")).toBeNull();
    expect(messageNameFromRoute("/")).toBeNull();
  });

  it("names nothing when the address goes deeper than one message", () => {
    // The route is registered as a splat, so it is reached with whatever follows
    expect(messageNameFromRoute("/admin/mail/abc/attachments")).toBeNull();
  });
});

describe("fetchCatcherStatus", () => {
  afterEach(() => { vi.restoreAllMocks(); });

  it("reads whether mail is being caught, and how much has been", async () => {
    const fetchMock = answering({ enabled: true, total: 3 });

    expect(await fetchCatcherStatus(fetchMock)).toEqual({ enabled: true, total: 3 });
    expect(fetchMock).toHaveBeenCalledWith("/CaughtMail.status.json");
  });

  it("reads an instance that is delivering normally", async () => {
    expect(await fetchCatcherStatus(answering({ enabled: false, total: 0 })))
      .toEqual({ enabled: false, total: 0 });
  });

  it("treats an answer missing either half as the safe reading", async () => {
    // Claiming mail is being caught when the answer did not say so would send somebody looking for
    // a message that was in fact delivered
    expect(await fetchCatcherStatus(answering({}))).toEqual({ enabled: false, total: 0 });
  });

  it("refuses an answer it could not read, carrying the status", async () => {
    await expect(fetchCatcherStatus(answering(null, 403)))
      .rejects.toThrow("The caught mail could not be read (403)");
  });

  it("refuses an empty answer", async () => {
    await expect(fetchCatcherStatus(answering(null))).rejects.toThrow("The caught mail could not be read");
  });
});

describe("fetchCaughtMessage", () => {
  afterEach(() => { vi.restoreAllMocks(); });

  const STORED = {
    "@path": "/CaughtMail/abc",
    "@name": "abc",
    "subject": "Your proposal has been approved",
    "caughtAt": "2026-08-20T18:30:00.000+00:00",
    "from": [ "IAP <iap@uhn.ca>" ],
    "to": [ "Someone <someone@uhn.ca>", "other@uhn.ca" ],
    "cc": [ "reb@uhn.ca" ],
    "bcc": [ "audit@uhn.ca" ],
    "replyTo": [ "no-reply@uhn.ca" ],
    "headers": [ "Message-ID: <1@uhn.ca>", "Date: Thu, 20 Aug 2026 18:30:00 +0000" ],
    "textBody": "Approved.",
    "htmlBody": "<p>Approved.</p>",
  };

  it("reads a whole message from its own node", async () => {
    const fetchMock = answering(STORED);
    const message = await fetchCaughtMessage(fetchMock, "abc");

    expect(fetchMock).toHaveBeenCalledWith("/CaughtMail/abc.json");
    expect(message.path).toBe("/CaughtMail/abc");
    expect(message.name).toBe("abc");
    expect(message.subject).toBe("Your proposal has been approved");
    expect(message.to).toEqual([ "Someone <someone@uhn.ca>", "other@uhn.ca" ]);
    expect(message.cc).toEqual([ "reb@uhn.ca" ]);
    expect(message.bcc).toEqual([ "audit@uhn.ca" ]);
    expect(message.replyTo).toEqual([ "no-reply@uhn.ca" ]);
    expect(message.headers).toHaveLength(2);
    expect(message.textBody).toBe("Approved.");
    expect(message.htmlBody).toBe("<p>Approved.</p>");
  });

  it("reads a single address serialized as a bare string", async () => {
    // A message to one recipient is the common case, and the serializer does not wrap it in a list
    const message = await fetchCaughtMessage(answering({ to: "someone@uhn.ca" }), "abc");

    expect(message.to).toEqual([ "someone@uhn.ca" ]);
  });

  it("passes over anything in an address list that is not one", async () => {
    const message = await fetchCaughtMessage(answering({ to: [ "someone@uhn.ca", 42 ] }), "abc");

    expect(message.to).toEqual([ "someone@uhn.ca" ]);
  });

  it("leaves absent properties absent rather than inventing empty ones", async () => {
    // A message with no subject and no HTML body is a message, not a broken one, and the viewer
    // shows different things for "empty" and "not there"
    const message = await fetchCaughtMessage(answering({ textBody: "Hello." }), "abc");

    expect(message.subject).toBeUndefined();
    expect(message.htmlBody).toBeUndefined();
    expect(message.caughtAt).toBeUndefined();
    expect(message.from).toEqual([]);
    expect(message.headers).toEqual([]);
  });

  it("falls back to where the message must be when the answer does not say", async () => {
    expect((await fetchCaughtMessage(answering({}), "abc")).path).toBe("/CaughtMail/abc");
  });

  it("refuses a message it could not read, carrying the status", async () => {
    await expect(fetchCaughtMessage(answering(null, 404), "abc"))
      .rejects.toThrow("The message could not be read (404)");
  });

  it("refuses an empty answer", async () => {
    await expect(fetchCaughtMessage(answering(null), "abc")).rejects.toThrow("The message could not be read");
  });
});
