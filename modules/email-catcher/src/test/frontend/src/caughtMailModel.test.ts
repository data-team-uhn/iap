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
  messageNameFromRoute,
  messagePath,
  messageRoute,
  parseCatcherStatus,
  parseCaughtMessage,
} from "@iap/email-catcher/caughtMailModel";

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

  it("addresses a message where it is stored, which is not where it is read", () => {
    expect(messagePath("abc")).toBe("/CaughtMail/abc");
    expect(messageRoute("abc")).toBe("/admin/mail/abc");
  });
});

describe("parseCatcherStatus", () => {
  it("reads whether mail is being caught, and how much has been", () => {
    expect(parseCatcherStatus({ enabled: true, total: 3 })).toEqual({ enabled: true, total: 3 });
  });

  it("reads an instance that is delivering normally", () => {
    expect(parseCatcherStatus({ enabled: false, total: 0 })).toEqual({ enabled: false, total: 0 });
  });

  it("treats an answer missing either half as the safe reading", () => {
    // Claiming mail is being caught when the answer did not say so would send somebody looking for
    // a message that was in fact delivered
    expect(parseCatcherStatus({})).toEqual({ enabled: false, total: 0 });
  });

  it("does not take a count, or a switch, of the wrong type", () => {
    expect(parseCatcherStatus({ enabled: "yes", total: "3" })).toEqual({ enabled: false, total: 0 });
  });
});

describe("parseCaughtMessage", () => {
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

  it("reads a whole message from its own node", () => {
    const message = parseCaughtMessage(STORED, "abc");

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

  it("reads a single address serialized as a bare string", () => {
    // A message to one recipient is the common case, and the serializer does not wrap it in a list
    expect(parseCaughtMessage({ to: "someone@uhn.ca" }, "abc").to).toEqual([ "someone@uhn.ca" ]);
  });

  it("passes over anything in an address list that is not one", () => {
    expect(parseCaughtMessage({ to: [ "someone@uhn.ca", 42 ] }, "abc").to)
      .toEqual([ "someone@uhn.ca" ]);
  });

  it("leaves absent properties absent rather than inventing empty ones", () => {
    // A message with no subject and no HTML body is a message, not a broken one, and the viewer
    // shows different things for "empty" and "not there"
    const message = parseCaughtMessage({ textBody: "Hello." }, "abc");

    expect(message.subject).toBeUndefined();
    expect(message.htmlBody).toBeUndefined();
    expect(message.caughtAt).toBeUndefined();
    expect(message.from).toEqual([]);
    expect(message.headers).toEqual([]);
  });

  it("treats an empty string as absent rather than as a subject nobody wrote", () => {
    expect(parseCaughtMessage({ subject: "" }, "abc").subject).toBeUndefined();
  });

  it("falls back to where the message must be when the answer does not say", () => {
    expect(parseCaughtMessage({}, "abc").path).toBe("/CaughtMail/abc");
  });
});
