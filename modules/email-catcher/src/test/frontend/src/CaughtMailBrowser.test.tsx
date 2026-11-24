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
import { MemoryRouter } from "react-router";

import CaughtMailBrowser from "@iap/email-catcher/CaughtMailBrowser";
import { jsonResponse, stubFetch } from "@iap/frontend-commons/fetch.fixture";

const MESSAGE_ROW = {
  "@path": "/CaughtMail/abc",
  "@name": "abc",
  "sling:resourceType": "mail/CaughtMessage",
  "subject": "Your proposal has been approved",
  "from": [ "IAP <iap@uhn.ca>" ],
  "to": [ "someone@uhn.ca" ],
  "caughtAt": "2026-08-20T18:30:00.000+00:00",
};

const page = (rows: unknown[]) => ({
  rows, offset: 0, limit: 25, returnedrows: rows.length, totalrows: rows.length, totalIsApproximate: false,
});

/** Answers both the catcher's state and the listing. */
const answering = (enabled: boolean, rows: unknown[] = [ MESSAGE_ROW ]) => stubFetch(({ url }) => jsonResponse(
  url.includes(".adminSummary.json")
    ? { enabled: { label: "Catching mail", value: enabled }, total: { label: "Caught so far", value: rows.length } }
    : page(rows)));

const browser = () => render(<CaughtMailBrowser />, { wrapper: MemoryRouter });

/** The listing requests made so far, as URLs. */
const listings = (fetchMock: { mock: { calls: [string, ...unknown[]][] } }) =>
  fetchMock.mock.calls.map(call => call[0]).filter(url => url.includes(".paginate.json"));

describe("CaughtMailBrowser", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("lists the caught messages from the catcher's own folder, newest first", async () => {
    const fetchMock = answering(true);
    browser();

    expect(await screen.findByText("Your proposal has been approved")).toBeInTheDocument();
    expect(screen.getByText("someone@uhn.ca")).toBeInTheDocument();

    const url = new URL(listings(fetchMock)[0], "http://localhost");
    expect(url.pathname).toBe("/CaughtMail.paginate.json");
    expect(url.searchParams.get("sortBy")).toBe("caughtAt");
    expect(url.searchParams.get("descending")).toBe("true");
  });

  it("says mail is being delivered normally, and where to switch that", async () => {
    answering(false, []);
    browser();

    expect(await screen.findByText(/Mail is being delivered normally/)).toHaveTextContent(/administration dashboard/);
    expect(screen.getByText("IAP Email Catcher")).toBeInTheDocument();
    expect(screen.getByText("Not catching")).toBeInTheDocument();
  });

  it("says nothing about delivery on an instance that is catching", async () => {
    answering(true, []);
    browser();

    expect(await screen.findByText("Catching mail")).toBeInTheDocument();
    expect(screen.queryByText(/Mail is being delivered normally/)).not.toBeInTheDocument();
  });

  it("says the list is empty rather than looking like it failed", async () => {
    answering(true, []);
    browser();

    expect(await screen.findByText("Nothing has been caught yet.")).toBeInTheDocument();
  });

  it("still lists the messages when the catcher's state cannot be read", async () => {
    stubFetch(({ url }) => url.includes(".adminSummary.json")
      ? new Response("", { status: 403 })
      : jsonResponse(page([ MESSAGE_ROW ])));
    browser();

    expect(await screen.findByText("Your proposal has been approved")).toBeInTheDocument();
    await waitFor(() => { expect(screen.queryByText("Catching mail")).not.toBeInTheDocument(); });
    expect(screen.queryByText("Not catching")).not.toBeInTheDocument();
  });
});
