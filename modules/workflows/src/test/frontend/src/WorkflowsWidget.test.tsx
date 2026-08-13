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

import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import { SESSION_INFO_URL } from "@iap/frontend-commons/reLogin";
import WorkflowsWidget from "@iap/workflows/WorkflowsWidget";

const listing = {
  "jcr:primaryType": "wf:WorkflowsHomepage",
  "review": {
    "jcr:primaryType": "wf:WorkflowDefinition",
    "title": "Standard review",
    "1-0": {
      "jcr:primaryType": "wf:WorkflowVersion",
      "version": "1.0",
      "description": "The initial cut",
    },
    "2-0": {
      "jcr:primaryType": "wf:WorkflowVersion",
      "version": "2.0",
    },
  },
};

// The listing goes through useAuthenticatedFetch, so the answer carries the `url` it came back from:
// that is how the login page an expired session is redirected to is recognised.
const stubFetch = (body: unknown) =>
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve({
    ok: true, status: 200, statusText: "OK", url,
    json: () => Promise.resolve(body),
  } as unknown as Response)));

// A server that answers everything with the same failure, while reporting the session as live: what
// makes the failure the server's own rather than a lapsed session to be recovered from.
const stubFailingFetch = (status: number, statusText: string) =>
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve((url === SESSION_INFO_URL
    ? { ok: true, status: 200, url, json: () => Promise.resolve({ userID: "admin" }) }
    : { ok: false, status, statusText, url }) as unknown as Response)));

afterEach(() => vi.unstubAllGlobals());

describe("WorkflowsWidget", () => {
  it("lists one line per workflow version, with the description as detail", async () => {
    stubFetch(listing);
    render(<WorkflowsWidget />);

    expect(await screen.findByText("Standard review (v1.0)")).toBeInTheDocument();
    expect(screen.getByText("Standard review (v2.0)")).toBeInTheDocument();
    expect(screen.getByText("The initial cut")).toBeInTheDocument();
  });

  it("reports a loading failure without crashing the console, and says why", async () => {
    stubFailingFetch(500, "Server Error");
    render(<WorkflowsWidget />);

    const report = await screen.findByRole("alert");
    expect(report).toHaveTextContent("The workflows could not be loaded");
    // The cause, which the widget used to discard
    expect(report).toHaveTextContent("The server ran into a problem");
    expect(report).toHaveTextContent("(HTTP 500)");
  });

  it("reloads the listing when the load failure's Retry is used", async () => {
    stubFailingFetch(500, "Server Error");
    render(<WorkflowsWidget />);
    await screen.findByRole("alert");

    // The next attempt succeeds
    stubFetch(listing);
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByText("Standard review (v1.0)")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
  });

  it("reports an empty workflow list", async () => {
    stubFetch({ "jcr:primaryType": "wf:WorkflowsHomepage" });
    render(<WorkflowsWidget />);

    expect(await screen.findByText("No workflows are defined yet.")).toBeInTheDocument();
  });
});
