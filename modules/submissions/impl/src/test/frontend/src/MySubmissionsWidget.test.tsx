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

// The extension the dashboard hands the widget, which is where its title and subtitle come from
const EXTENSION = {
  "iap:extensionName": "My Submissions",
  "iap:subtitle": "The submissions you created",
};

const SUBMISSION_PATH = "/Submissions/ab/cd/ef/0a1b2c3d-0000-0000-0000-000000000000";

const ROW = {
  "@path": SUBMISSION_PATH,
  "@name": "0a1b2c3d-0000-0000-0000-000000000000",
  "title": "Test my drug",
  "tags": ["in-review"],
  "schemaVersion": { "@path": "/Schemas/ClinicalTrial/1.0", "@name": "1.0", "version": "1.0" },
  "jcr:created": "2026-07-01T10:00:00.000-04:00",
  "jcr:lastModified": "2026-07-02T10:00:00.000-04:00",
};

const SCHEMAS = {
  timeOffRequest: {
    "@path": "/Schemas/timeOffRequest",
    "@name": "timeOffRequest",
    "title": "Time off request",
    "active": true,
    "v1": { "@path": "/Schemas/timeOffRequest/v1", "@name": "v1", "version": "1.0", "active": true },
  },
};

function page(rows: unknown[]) {
  return { rows, offset: 0, limit: 5, returnedrows: rows.length, totalrows: rows.length, totalIsApproximate: false };
}

function json(body: unknown) {
  // A real Response rather than an object literal: the deletion controls fetch through
  // useAuthenticatedFetch, which reads `response.url` to tell an expired session from an answer, and
  // a literal without one throws there instead
  return Promise.resolve(new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  }));
}

// A fetch covering everything the widget and the controls inside it ask for: the listing, the
// schemas on offer, the POST that raises a submission, and the two DELETEs (the dry run that the
// confirmation is built from, then the deletion itself).
function widgetFetch(options: { rows?: unknown[]; redirected?: boolean } = {}) {
  const { rows = [], redirected = true } = options;
  return vi.fn((input: string | URL, init?: { method?: string }) => {
    const url = String(input);
    if (init?.method === "DELETE") {
      return json(url.includes("dryRun=true")
        ? { "status.code": 200, "status": "dryRun", "executable": true, "items": [ SUBMISSION_PATH ] }
        : { "status.code": 200, "status": "archived", "archiveEntry": "/Archive/ab/cd/ef/entry" });
    }
    if (url === "/Submissions" && init?.method === "POST") {
      return Promise.resolve({
        ok: true,
        status: 200,
        redirected,
        url: redirected ? `http://localhost${SUBMISSION_PATH}` : "http://localhost/Submissions",
        json: () => Promise.resolve({}),
      } as unknown as Response);
    }
    if (url.startsWith("/Schemas")) {
      return json(SCHEMAS);
    }
    return tagAwareFetch(page(rows))(url);
  });
}

describe("MySubmissionsWidget", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    clearTagDefinitionsCache();
  });

  it("lists the current user's submissions with the schema and status columns", async () => {
    const fetchMock = widgetFetch({ rows: [ ROW ] });
    vi.stubGlobal("fetch", fetchMock);

    render(<MySubmissionsWidget extension={EXTENSION} />, { wrapper: MemoryRouter });

    expect(await screen.findByText("Test my drug")).toBeInTheDocument();
    expect(screen.getByText("ClinicalTrial 1.0")).toBeInTheDocument();
    // The lifecycle tag, displayed per its /Tags definition
    expect(await screen.findByText("In review")).toBeInTheDocument();

    const url = new URL(String(fetchMock.mock.calls[0][0]), "http://localhost");
    expect(url.pathname).toBe("/Submissions.paginate.json");
    // `createdBy` and not `jcr:createdBy`: the engine writes every submission as its own service user,
    // so the JCR property names the engine on every row and this widget would list nothing. Nor is it
    // worth ORing in as a fallback — it can only name a user who wrote content directly, which is what
    // the engine exists to prevent
    expect(url.searchParams.getAll("fieldName")).toEqual(["createdBy"]);
    expect(url.searchParams.getAll("fieldValue")).toEqual(["@me"]);
    // Newest activity first by default
    expect(url.searchParams.get("sortBy")).toBe("jcr:lastModified");
    expect(url.searchParams.get("descending")).toBe("true");
  });

  it("draws its own header, so the action sits on the title's line", async () => {
    // The dashboard is told to skip its own header for this widget; the title and subtitle still
    // come from the extension, so they are declared in exactly one place
    vi.stubGlobal("fetch", widgetFetch());

    render(<MySubmissionsWidget extension={EXTENSION} />, { wrapper: MemoryRouter });

    expect(await screen.findByRole("heading", { name: "My Submissions" })).toBeInTheDocument();
    expect(screen.getByText("The submissions you created")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /New submission/ })).toBeInTheDocument();
  });

  it("offers the actions on each row, and re-reads the listing once one is deleted", async () => {
    const fetchMock = widgetFetch({ rows: [ ROW ] });
    vi.stubGlobal("fetch", fetchMock);
    const listings = () =>
      fetchMock.mock.calls.filter(([ url ]) => String(url).includes(".paginate.json")).length;

    render(<MySubmissionsWidget extension={EXTENSION} />, { wrapper: MemoryRouter });
    await screen.findByText("Test my drug");

    expect(screen.getByRole("button", { name: "View" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit" })).toBeInTheDocument();

    const before = listings();
    await userEvent.click(screen.getByRole("button", { name: /^Delete/ }));
    // Scoped to the confirmation, whose own button is also called Delete
    const confirmation = within(await screen.findByRole("dialog"));
    await userEvent.click(confirmation.getByRole("button", { name: "Delete" }));

    // The row is gone from the server, and the grid has no way of knowing that unless it is told
    await waitFor(() => expect(listings()).toBeGreaterThan(before));
  });

  it("offers raising a new submission, and opens the one that was raised", async () => {
    const fetchMock = widgetFetch();
    vi.stubGlobal("fetch", fetchMock);

    render(<MySubmissionsWidget extension={EXTENSION} />, { wrapper: MemoryRouter });

    // The dialog is only mounted once it is asked for, so nothing reads the schemas on the way to
    // showing a dashboard that may never open it
    expect(fetchMock.mock.calls.some(([ url ]) => String(url).startsWith("/Schemas"))).toBe(false);

    await raiseOne();

    // The dialog closes and the new submission's own page is opened, which is also what makes the
    // listing behind it current again when the submitter comes back
    await waitFor(() => expect(screen.queryByRole("radio")).not.toBeInTheDocument());
  });

  it("stays on the dashboard when the engine created nothing to open", async () => {
    // A plain 200 rather than a redirect means the delivery was accepted without raising anything,
    // so there is nowhere to send the submitter and the dashboard is where they stay
    vi.stubGlobal("fetch", widgetFetch({ redirected: false }));

    render(<MySubmissionsWidget extension={EXTENSION} />, { wrapper: MemoryRouter });
    await raiseOne();

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: /New submission/ })).toBeInTheDocument();
  });

  it("renders without a title when the dashboard passes no extension", async () => {
    vi.stubGlobal("fetch", widgetFetch());

    render(<MySubmissionsWidget />, { wrapper: MemoryRouter });

    expect(await screen.findByRole("button", { name: /New submission/ })).toBeInTheDocument();
    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
  });
});

async function raiseOne() {
  await userEvent.click(screen.getByRole("button", { name: /New submission/ }));
  // Scoped to the dialog: the grid behind it has a "Title" column, whose own menu button would
  // otherwise match too
  const dialog = within(await screen.findByRole("dialog"));
  await userEvent.click(dialog.getByRole("radio"));
  await userEvent.type(dialog.getByLabelText(/Title/), "A long weekend");
  await userEvent.click(dialog.getByRole("button", { name: "Create" }));
}
