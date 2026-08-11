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

import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";

import MySubmissionsWidget from "@iap/submissions/MySubmissionsWidget";
import { clearTagDefinitionsCache } from "@iap/tags/tagDefinitions";
import { tagAwareFetch } from "@iap/tags/tagDefinitions.fixture";

describe("MySubmissionsWidget", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    clearTagDefinitionsCache();
  });

  it("lists the current user's submissions with the schema and status columns", async () => {
    const page = {
      rows: [{
        "@path": "/Submissions/s1",
        "@name": "s1",
        "title": "Test my drug",
        "tags": ["in-review"],
        "schemaVersion": { "@path": "/Schemas/ClinicalTrial/1.0", "@name": "1.0", "version": "1.0" },
        "jcr:created": "2026-07-01T10:00:00.000-04:00",
        "jcr:lastModified": "2026-07-02T10:00:00.000-04:00",
      }],
      offset: 0,
      limit: 5,
      returnedrows: 1,
      totalrows: 1,
      totalIsApproximate: false,
    };
    const fetchMock = vi.fn(tagAwareFetch(page));
    vi.stubGlobal("fetch", fetchMock);

    render(<MySubmissionsWidget />, { wrapper: MemoryRouter });

    expect(await screen.findByText("Test my drug")).toBeInTheDocument();
    expect(screen.getByText("ClinicalTrial 1.0")).toBeInTheDocument();
    // The lifecycle tag, displayed per its /Tags definition
    expect(await screen.findByText("In review")).toBeInTheDocument();

    const url = new URL(fetchMock.mock.calls[0][0], "http://localhost");
    expect(url.pathname).toBe("/Submissions.paginate.json");
    // `createdBy` and not `jcr:createdBy`: the workflow engine writes submissions as its own
    // service user, so the JCR property names the engine on every row and this widget would list
    // nothing. The property recording the person it acted for is the one to select on.
    expect(url.searchParams.getAll("fieldName")).toEqual(["createdBy"]);
    expect(url.searchParams.getAll("fieldValue")).toEqual(["@me"]);
    // Newest activity first by default
    expect(url.searchParams.get("sortBy")).toBe("jcr:lastModified");
    expect(url.searchParams.get("descending")).toBe("true");
  });

  // A fetch covering everything the widget and its dialog ask for: the listing, the schemas on
  // offer, and the POST that raises the submission. `redirected` is what says whether the engine
  // created something to open.
  function widgetFetch(redirected: boolean) {
    const schemas = {
      timeOffRequest: {
        "@path": "/Schemas/timeOffRequest",
        "@name": "timeOffRequest",
        "title": "Time off request",
        "active": true,
        "v1": { "@path": "/Schemas/timeOffRequest/v1", "@name": "v1", "version": "1.0", "active": true },
      },
    };
    const listing = { rows: [], offset: 0, limit: 5, returnedrows: 0, totalrows: 0, totalIsApproximate: false };
    return vi.fn((url: string, options?: { method?: string }) => {
      if (url === "/Submissions" && options?.method === "POST") {
        return Promise.resolve({
          ok: true,
          status: 200,
          redirected,
          url: redirected ? "http://localhost/Submissions/aLongWeekend" : "http://localhost/Submissions",
          json: () => Promise.resolve({}),
        } as unknown as Response);
      }
      if (url.startsWith("/Schemas")) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve(schemas) } as unknown as Response);
      }
      return tagAwareFetch(listing)(url);
    });
  }

  async function raiseOne() {
    await userEvent.click(screen.getByRole("button", { name: /New submission/ }));
    // Scoped to the dialog: the grid behind it has a "Title" column, whose own menu button would
    // otherwise match too
    const dialog = within(await screen.findByRole("dialog"));
    await userEvent.click(dialog.getByRole("radio"));
    await userEvent.type(dialog.getByLabelText(/Title/), "A long weekend");
    await userEvent.click(dialog.getByRole("button", { name: "Create" }));
  }

  it("offers raising a new submission, and opens the one that was raised", async () => {
    const fetchMock = widgetFetch(true);
    vi.stubGlobal("fetch", fetchMock);

    render(<MySubmissionsWidget />, { wrapper: MemoryRouter });

    // The dialog is only mounted once it is asked for, so nothing reads the schemas on the way to
    // showing a dashboard that may never open it
    expect(fetchMock.mock.calls.some(([ url ]) => url.startsWith("/Schemas"))).toBe(false);

    await raiseOne();

    // The dialog closes and the new submission's own page is opened, which is also what makes the
    // listing behind it current again when the submitter comes back
    await waitFor(() => expect(screen.queryByRole("radio")).not.toBeInTheDocument());
  });

  it("stays on the dashboard when the engine created nothing to open", async () => {
    // A plain 200 rather than a redirect means the delivery was accepted without raising anything,
    // so there is nowhere to send the submitter and the dashboard is where they stay
    vi.stubGlobal("fetch", widgetFetch(false));

    render(<MySubmissionsWidget />, { wrapper: MemoryRouter });
    await raiseOne();

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: /New submission/ })).toBeInTheDocument();
  });
});
