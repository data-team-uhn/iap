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
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";

import { clearActions } from "@iap/frontend-commons/actionsManager";
import { appTheme } from "@iap/frontend-commons/appTheme";
import { SESSION_INFO_URL } from "@iap/frontend-commons/reLogin";
import { loadExtensions } from "@iap/ui-extension/extensionManager";
import WorkflowManager from "@iap/workflows/WorkflowManager";
import type { WorkflowVersionActionProps } from "@iap/workflows/WorkflowVersionActions";

vi.mock("@iap/ui-extension/extensionManager", () => ({ loadExtensions: vi.fn() }));

const mockedLoadExtensions = vi.mocked(loadExtensions);

// A stubbed fetch: the URL, and the request options a write carries.
type FetchStub = (url: string, options?: RequestInit) => Promise<Response>;

const WORKFLOW_PATH = "/Workflows/review";

const definition = {
  "jcr:primaryType": "wf:WorkflowDefinition",
  "title": "Standard review",
  "jcr:created": "2026-07-01T09:00:00.000Z",
  "jcr:lastModified": "2026-08-02T11:30:00.000Z",
  "1-0": {
    "jcr:primaryType": "wf:WorkflowVersion",
    "version": "1.0",
    "description": "The initial cut",
    "state": "RETIRED",
  },
  "2-0": {
    "jcr:primaryType": "wf:WorkflowVersion",
    "version": "2.0",
    "state": "ACTIVE",
  },
  "3-0": {
    "jcr:primaryType": "wf:WorkflowVersion",
    "version": "3.0",
    "state": "DRAFT",
  },
};

// A server answering the workflow's own listing, and nothing else of consequence.
const stubFetch = (body: unknown = definition) => {
  const fetchMock = vi.fn<FetchStub>((url, options) => Promise.resolve({
    ok: true,
    status: 200,
    // An event that created something answers with a redirect to it, which fetch follows on its own,
    // so the final URL is where the caller reads the new path from
    redirected: options?.method === "POST",
    url: `http://localhost${url.split(".")[0]}/created`,
    headers: new Headers(),
    json: () => Promise.resolve(body),
  } as unknown as Response));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
};

// A server that refuses everything while reporting the session as live: what makes a failure the
// server's own rather than a lapsed session to be recovered from.
const stubFailingFetch = (status: number) => {
  vi.stubGlobal("fetch", vi.fn<FetchStub>(url => Promise.resolve((url === SESSION_INFO_URL
    ? { ok: true, status: 200, url, json: () => Promise.resolve({ userID: "admin" }) }
    : {
      ok: false,
      status,
      statusText: "Refused",
      url,
      // The engine explains a refused event; a bare status would leave that to be invented
      json: () => Promise.resolve({ error: `The engine refused this (${status})` }),
    }) as unknown as Response)));
};

const renderManager = () => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <MemoryRouter initialEntries={[`/admin/workflows${WORKFLOW_PATH}`]}>
      <WorkflowManager path={WORKFLOW_PATH} />
    </MemoryRouter>
  </ThemeProvider>
);

// An action that shows which version it was handed, standing in for the ones the repository
// contributes.
const labellingAction = ({ version }: WorkflowVersionActionProps) => <span>{`acts on ${version.version}`}</span>;

beforeEach(() => {
  clearActions();
  mockedLoadExtensions.mockResolvedValue([]);
});

afterEach(() => vi.unstubAllGlobals());

describe("WorkflowManager", () => {
  it("displays the workflow's own properties", async () => {
    stubFetch();

    renderManager();

    expect(await screen.findByRole("heading", { name: "Standard review" })).toBeInTheDocument();
    expect(screen.getByText(WORKFLOW_PATH)).toBeInTheDocument();
    // Enabled because one of its versions is active, which is the only thing that makes a workflow run
    expect(screen.getAllByText("Enabled").length).toBeGreaterThan(0);
  });

  it("says a workflow does not run while no version of it is active", async () => {
    // A trial is not the version instances are created from, so a workflow whose only version is on
    // trial runs nothing
    stubFetch({
      "jcr:primaryType": "wf:WorkflowDefinition",
      "title": "Standard review",
      "1-0": { "jcr:primaryType": "wf:WorkflowVersion", "version": "1.0", "state": "TRIAL" },
    });

    renderManager();

    expect(await screen.findByText("Disabled")).toBeInTheDocument();
    expect(screen.getByText("Trial")).toBeInTheDocument();
  });

  it("lists every version with its state", async () => {
    stubFetch();

    renderManager();

    const rows = await screen.findAllByRole("row");
    // The header, then one row per version, in the repository's own order
    expect(rows).toHaveLength(4);
    expect(within(rows[1]).getByText("1.0")).toBeInTheDocument();
    expect(within(rows[1]).getByText("Retired")).toBeInTheDocument();
    expect(within(rows[1]).getByText("The initial cut")).toBeInTheDocument();
    expect(within(rows[2]).getByText("Active")).toBeInTheDocument();
    expect(within(rows[3]).getByText("Draft")).toBeInTheDocument();
  });

  it("names a version by its node name when it carries no label", async () => {
    // A version whose label never made it into the repository still has to be identifiable
    stubFetch({
      "jcr:primaryType": "wf:WorkflowDefinition",
      "title": "Standard review",
      "unlabelled": { "jcr:primaryType": "wf:WorkflowVersion", "state": "DRAFT" },
    });

    renderManager();

    const rows = await screen.findAllByRole("row");
    expect(within(rows[1]).getByText("unlabelled")).toBeInTheDocument();
  });

  it("lets a reported message be dismissed", async () => {
    const user = userEvent.setup();
    const reportingAction = ({ report }: WorkflowVersionActionProps) => (
      <button type="button" onClick={() => report("Something happened")}>report</button>
    );
    mockedLoadExtensions.mockResolvedValue([ { "ext:render": reportingAction } ]);
    stubFetch();
    renderManager();
    await user.click((await screen.findAllByRole("button", { name: "report" }))[0]);
    await screen.findByText("Something happened");

    await user.click(screen.getByRole("button", { name: "Dismiss" }));

    await waitFor(() => expect(screen.queryByText("Something happened")).not.toBeInTheDocument());
  });

  it("says so when the workflow has no versions yet", async () => {
    stubFetch({ "jcr:primaryType": "wf:WorkflowDefinition", title: "Empty" });

    renderManager();

    expect(await screen.findByText("This workflow has no versions yet.")).toBeInTheDocument();
  });

  it("renders the contributed actions against each version", async () => {
    // The buttons are not written into this page: a module adds one by shipping an extension, which
    // is what makes a later action possible without touching the manager
    mockedLoadExtensions.mockResolvedValue([ { "ext:render": labellingAction } ]);
    stubFetch();

    renderManager();

    expect(await screen.findByText("acts on 1.0")).toBeInTheDocument();
    expect(screen.getByText("acts on 2.0")).toBeInTheDocument();
    expect(screen.getByText("acts on 3.0")).toBeInTheDocument();
    expect(mockedLoadExtensions).toHaveBeenCalledWith("WorkflowVersionActions");
  });

  it("shows what an action reports, and reads the workflow again when one asks", async () => {
    // An action that changed something says so through the page, which is the only place with room
    // for a message and the only one that can refresh the listing
    const user = userEvent.setup();
    const reportingAction = ({ reload, report }: WorkflowVersionActionProps) => (
      <button type="button" onClick={() => { report("Version 2.0 is now active"); reload(); }}>report</button>
    );
    mockedLoadExtensions.mockResolvedValue([ { "ext:render": reportingAction } ]);
    const fetchMock = stubFetch();
    renderManager();
    const buttons = await screen.findAllByRole("button", { name: "report" });
    const listingsBefore = fetchMock.mock.calls.length;

    await user.click(buttons[0]);

    expect(await screen.findByText("Version 2.0 is now active")).toBeInTheDocument();
    expect(fetchMock.mock.calls.length).toBeGreaterThan(listingsBefore);
  });

  it("reports a load failure, and says why", async () => {
    stubFailingFetch(500);

    renderManager();

    const report = await screen.findByRole("alert");
    expect(report).toHaveTextContent("This workflow could not be loaded");
    expect(report).toHaveTextContent("(HTTP 500)");
  });

  it("reloads when the load failure's Retry is used", async () => {
    const user = userEvent.setup();
    stubFailingFetch(500);
    renderManager();
    await screen.findByRole("alert");

    stubFetch();
    await user.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByRole("heading", { name: "Standard review" })).toBeInTheDocument();
  });

  it("saves an edit of the workflow's properties and shows the result", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch();
    renderManager();
    await screen.findByRole("heading", { name: "Standard review" });

    await user.click(screen.getByRole("button", { name: "Edit properties" }));
    const dialog = await screen.findByRole("dialog", { name: "Workflow properties" });
    await user.clear(within(dialog).getByRole("textbox", { name: /Title/ }));
    await user.type(within(dialog).getByRole("textbox", { name: /Title/ }), "Reviewed twice");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const save = fetchMock.mock.calls.find(call => call[1]?.method === "POST");
    expect(save?.[0]).toBe(WORKFLOW_PATH);
    // The title and nothing else: whether the workflow runs is read off its versions
    expect(Object.fromEntries((save?.[1]?.body as URLSearchParams).entries())).toEqual({
      title: "Reviewed twice",
    });
  });

  it("creates a version and opens its editor", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch();
    renderManager();
    await screen.findByRole("heading", { name: "Standard review" });

    await user.click(screen.getByRole("button", { name: "New version" }));
    const dialog = await screen.findByRole("dialog", { name: /New version/ });
    await user.type(within(dialog).getByRole("textbox", { name: /Version/ }), "4.0");
    await user.type(within(dialog).getByRole("textbox", { name: /Description/ }), "With an escalation");
    await user.click(within(dialog).getByRole("button", { name: "Create" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const create = fetchMock.mock.calls.find(call => call[0] === `${WORKFLOW_PATH}.createVersion.json`);
    const body = create?.[1]?.body as FormData;
    expect(body.get("version")).toBe("4.0");
    expect(body.get("description")).toBe("With an escalation");
    // The diagram travels with the request; what state the version starts in is the definition's
    expect(body.get("bpmn.xml")).toBeInstanceOf(File);
    expect(body.get("state")).toBeNull();
  });

  it("keeps the properties dialog open and says why when the save is refused", async () => {
    const user = userEvent.setup();
    stubFetch();
    renderManager();
    await screen.findByRole("heading", { name: "Standard review" });
    await user.click(screen.getByRole("button", { name: "Edit properties" }));
    const dialog = await screen.findByRole("dialog", { name: "Workflow properties" });

    stubFailingFetch(403);
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("The engine refused this (403)");
    expect(screen.getByRole("dialog", { name: "Workflow properties" })).toBeInTheDocument();
  });

  it("keeps the new-version dialog open and says why when the creation is refused", async () => {
    const user = userEvent.setup();
    stubFetch();
    renderManager();
    await screen.findByRole("heading", { name: "Standard review" });
    await user.click(screen.getByRole("button", { name: "New version" }));
    const dialog = await screen.findByRole("dialog", { name: /New version/ });
    await user.type(within(dialog).getByRole("textbox", { name: /Version/ }), "4.0");

    stubFailingFetch(500);
    await user.click(within(dialog).getByRole("button", { name: "Create" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("The engine refused this (500)");
  });

  it("refuses a version label the workflow already uses", async () => {
    const user = userEvent.setup();
    stubFetch();
    renderManager();
    await screen.findByRole("heading", { name: "Standard review" });

    await user.click(screen.getByRole("button", { name: "New version" }));
    const dialog = await screen.findByRole("dialog", { name: /New version/ });
    await user.type(within(dialog).getByRole("textbox", { name: /Version/ }), "2.0");

    expect(within(dialog).getByText("This workflow already has a version with that label")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Create" })).toBeDisabled();
  });
});
