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
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";

import LoggedErrorsBrowser from "@iap/error-tracking/LoggedErrorsBrowser";
import { clearTagDefinitionsCache } from "@iap/tags/tagDefinitions";

/** The four error-triage definitions this module ships, as /Tags.search.json answers them. */
const TRIAGE_DEFINITIONS = [
  { name: "unacknowledged", label: "Needs attention", color: "#c62828", order: 90, category: [ "error-triage" ] },
  { name: "acknowledged", label: "Acknowledged", color: "#607d8b", order: 100, category: [ "error-triage" ] },
  { name: "known-issue", label: "Known issue", color: "#ffb300", order: 110, category: [ "error-triage" ] },
  { name: "wont-fix", label: "Won't fix", color: "#9e9e9e", order: 120, category: [ "error-triage" ] },
];

const ERROR_ROW = {
  "@path": "/LoggedErrors/abc",
  "@name": "abc",
  "sling:resourceType": "err/LoggedFailure",
  "component": "io.uhndata.iap.tags.internal.TagPropagationEditor",
  "operation": "computeTags",
  "type": "java.lang.IllegalStateException",
  "occurrences": 7,
  "computedTags": [ "unacknowledged" ],
  "lastOccurrence": "2026-08-20T18:30:00.000+00:00",
  "jcr:created": "2026-08-01T10:00:00.000+00:00",
};

const page = (rows: unknown[]) => ({
  rows, offset: 0, limit: 25, returnedrows: rows.length, totalrows: rows.length, totalIsApproximate: false,
});

/** Answers both the tag vocabulary and the listing, each with its own Response. */
const answering = (rows: unknown[] = [ ERROR_ROW ]) => vi.fn((url: string) => {
  const body = url.includes("/Tags.search.json")
    ? { tags: TRIAGE_DEFINITIONS, total: TRIAGE_DEFINITIONS.length }
    : page(rows);
  return Promise.resolve(new Response(JSON.stringify(body),
    { status: 200, headers: { "Content-Type": "application/json" } }));
});

const browser = () => render(<LoggedErrorsBrowser />, { wrapper: MemoryRouter });

/** The listing requests made so far, as URLs. */
const listings = (fetchMock: { mock: { calls: [string, ...unknown[]][] } }) =>
  fetchMock.mock.calls.map(call => call[0]).filter(url => url.includes(".paginate.json"));

describe("LoggedErrorsBrowser", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    clearTagDefinitionsCache();
  });

  it("lists the recorded errors from their own homepage, newest fault first", async () => {
    const fetchMock = answering();
    vi.stubGlobal("fetch", fetchMock);
    browser();

    expect(await screen.findByText("IllegalStateException")).toBeInTheDocument();
    expect(screen.getByText("computeTags")).toBeInTheDocument();
    // The component is shown unqualified
    expect(screen.getByText("TagPropagationEditor")).toBeInTheDocument();

    const url = new URL(listings(fetchMock)[0], "http://localhost");
    expect(url.pathname).toBe("/LoggedErrors.paginate.json");
    expect(url.searchParams.get("sortBy")).toBe("lastOccurrence");
    expect(url.searchParams.get("descending")).toBe("true");
  });

  it("asks for everything until told otherwise", async () => {
    const fetchMock = answering();
    vi.stubGlobal("fetch", fetchMock);
    browser();

    await waitFor(() => { expect(listings(fetchMock)).not.toHaveLength(0); });
    const url = new URL(listings(fetchMock)[0], "http://localhost");
    expect(url.searchParams.getAll("fieldName")).not.toContain("computedTags");
  });

  it("narrows the listing to what needs attention when asked", async () => {
    // The grid's own column filter can express the same thing, but finding it takes several
    // clicks and this is the question somebody opening the page is nearly always asking
    const fetchMock = answering();
    vi.stubGlobal("fetch", fetchMock);
    browser();
    await screen.findByText("IllegalStateException");

    await userEvent.click(screen.getByRole("switch", { name: "Only what needs attention" }));

    await waitFor(() => {
      const latest = new URL(listings(fetchMock).at(-1)!, "http://localhost");
      expect(latest.searchParams.getAll("fieldName")).toContain("computedTags");
      expect(latest.searchParams.getAll("fieldValue")).toContain("unacknowledged");
    });
  });

  it("puts the listing back when the switch is turned off again", async () => {
    const fetchMock = answering();
    vi.stubGlobal("fetch", fetchMock);
    browser();
    await screen.findByText("IllegalStateException");
    const toggle = screen.getByRole("switch", { name: "Only what needs attention" });

    await userEvent.click(toggle);
    await waitFor(() => {
      expect(new URL(listings(fetchMock).at(-1)!, "http://localhost").searchParams.getAll("fieldName"))
        .toContain("computedTags");
    });

    await userEvent.click(toggle);
    await waitFor(() => {
      expect(new URL(listings(fetchMock).at(-1)!, "http://localhost").searchParams.getAll("fieldName"))
        .not.toContain("computedTags");
    });
  });

  it("says what an empty listing means, which depends on whether it was narrowed", async () => {
    const fetchMock = answering([]);
    vi.stubGlobal("fetch", fetchMock);
    browser();

    // "Nothing has been recorded" and "nothing needs attention" are different facts, and showing
    // the first while the switch is on would be wrong
    expect(await screen.findByText("Nothing has been recorded yet.")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("switch", { name: "Only what needs attention" }));
    expect(await screen.findByText("Nothing needs attention.")).toBeInTheDocument();
  });
});
