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
import { MemoryRouter, useLocation } from "react-router";

import { clearActions } from "@iap/frontend-commons/actionsManager";
import { appTheme } from "@iap/frontend-commons/appTheme";
import { NoticeProvider } from "@iap/frontend-commons/components/NoticeSnackbar";
import { jsonResponse, stubFailingFetch, stubFetch, type StubbedRequest } from "@iap/frontend-commons/fetch.fixture";
import { getPageCrumbs } from "@iap/frontend-commons/pageCrumbs";
import { loadExtensions } from "@iap/ui-extension/extensionManager";
import type { WorkflowActionProps } from "@iap/workflows/WorkflowActions";
import WorkflowManager from "@iap/workflows/WorkflowManager";
import type { WorkflowVersionActionProps } from "@iap/workflows/WorkflowVersionActions";

import { isTagSearch, LIFECYCLE_TAGS } from "./lifecycleTags.fixture";

vi.mock("@iap/ui-extension/extensionManager", () => ({ loadExtensions: vi.fn() }));

const mockedLoadExtensions = vi.mocked(loadExtensions);

const WORKFLOW_PATH = "/Workflows/review";

const definition = {
  "jcr:primaryType": "wf:WorkflowDefinition",
  "title": "Standard review",
  "jcr:created": "2026-07-01T09:00:00.000Z",
  "jcr:lastModified": "2026-08-02T11:30:00.000Z",
  "@events": [ "createVersion", "save" ],
  "1-0": {
    "jcr:primaryType": "wf:WorkflowVersion",
    "version": "1.0",
    "description": "The initial cut",
    "tags": ["retired"],
  },
  "2-0": {
    "jcr:primaryType": "wf:WorkflowVersion",
    "version": "2.0",
    "tags": ["active"],
  },
  "3-0": {
    "jcr:primaryType": "wf:WorkflowVersion",
    "version": "3.0",
    "tags": ["draft"],
  },
};

// What the server answers: an event with the path of what it created, a read with what it asked for.
const answerTo = (request: StubbedRequest, body: unknown) => {
  if (request.method === "POST") {
    return jsonResponse({ status: "created", path: `${request.url.split(".")[0]}/created` }, 201);
  }
  return jsonResponse(isTagSearch(request.url) ? LIFECYCLE_TAGS : body);
};

// A server answering the workflow's own listing, and nothing else of consequence.
const stubServer = (body: unknown = definition) => stubFetch(request => answerTo(request, body));

// A server that refuses everything. The engine explains a refused event; a bare status would leave
// that to be invented.
const stubRefusal = (status: number) =>
  stubFailingFetch(status, { body: { error: `The engine refused this (${status})` } });

// Where the router ended up
function Where() {
  return <div data-testid="where">{useLocation().pathname}</div>;
}

const renderManager = () => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <MemoryRouter initialEntries={[`/admin/workflows${WORKFLOW_PATH}`]}>
      <WorkflowManager path={WORKFLOW_PATH} homepage={{ path: "/Workflows", title: "Workflows" }} />
      <Where />
    </MemoryRouter>
  </ThemeProvider>,
  { wrapper: NoticeProvider },
);

// Makes MUI's useMediaQuery see a phone, switching the grid to its card list
function fakeNarrowScreen() {
  vi.stubGlobal("matchMedia", (query: string) => ({
    matches: query.includes("max-width"), media: query, onchange: null,
    addEventListener: () => undefined, removeEventListener: () => undefined,
    addListener: () => undefined, removeListener: () => undefined, dispatchEvent: () => false,
  }));
}

// An action that shows which version it was handed, standing in for the ones the repository
// contributes.
const labellingAction = ({ version }: WorkflowVersionActionProps) => <span>{`acts on ${version.version}`}</span>;

beforeEach(() => {
  clearActions();
  mockedLoadExtensions.mockResolvedValue([]);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("WorkflowManager", () => {
  it("displays the workflow's own properties", async () => {
    stubServer();

    renderManager();

    expect(await screen.findByRole("heading", { name: "Standard review" })).toBeInTheDocument();
    expect(screen.getByText(WORKFLOW_PATH)).toBeInTheDocument();
    // Enabled because one of its versions is active, which is the only thing that makes a workflow run
    expect(screen.getAllByText("Enabled").length).toBeGreaterThan(0);
  });

  it("leads back to its homepage, under the homepage's title", async () => {
    stubServer();

    renderManager();

    await screen.findByRole("heading", { name: "Standard review" });
    expect(getPageCrumbs()).toEqual([ { path: "/admin/workflows/Workflows", label: "Workflows" } ]);
  });

  it("says a workflow does not run while no version of it is active", async () => {
    // A trial is not the version instances are created from, so a workflow whose only version is on
    // trial runs nothing
    stubServer({
      "jcr:primaryType": "wf:WorkflowDefinition",
      "title": "Standard review",
      "1-0": { "jcr:primaryType": "wf:WorkflowVersion", "version": "1.0", "tags": ["trial"] },
    });

    renderManager();

    expect(await screen.findByText("Disabled")).toBeInTheDocument();
    expect(await screen.findByText("Trial")).toBeInTheDocument();
  });

  it("says a workflow is retired once its last active version is withdrawn", async () => {
    // A draft beside the retired version does not make it run: only activating a version does
    stubServer({
      "jcr:primaryType": "wf:WorkflowDefinition",
      "title": "Standard review",
      "1-0": { "jcr:primaryType": "wf:WorkflowVersion", "version": "1.0", "tags": ["retired"] },
      "2-0": { "jcr:primaryType": "wf:WorkflowVersion", "version": "2.0", "tags": ["draft"] },
    });

    renderManager();

    await screen.findByRole("heading", { name: "Standard review" });
    // Once as the workflow's status, once as the version's lifecycle
    await waitFor(() => expect(screen.getAllByText("Retired")).toHaveLength(2));
    expect(screen.queryByText("Disabled")).not.toBeInTheDocument();
    expect(screen.queryByText("Enabled")).not.toBeInTheDocument();
  });

  it("lists every version with its lifecycle", async () => {
    const fetchMock = stubServer();

    renderManager();

    const rows = await screen.findAllByRole("row");
    // One level deep: a row shows only a version's own properties, so serializing each version's
    // diagram and parsed graph as well would be fetching a whole workflow to draw a table row. And with
    // the events the server offers on each, which decide the actions
    expect(fetchMock.mock.calls[0][0]).toBe(`${WORKFLOW_PATH}.1.events.json`);
    // The header, then one row per version, in the repository's own order
    expect(rows).toHaveLength(4);
    expect(within(rows[1]).getByText("1.0")).toBeInTheDocument();
    expect(await within(rows[1]).findByText("Retired")).toBeInTheDocument();
    expect(within(rows[1]).getByText("The initial cut")).toBeInTheDocument();
    expect(within(rows[2]).getByText("Active")).toBeInTheDocument();
    expect(within(rows[3]).getByText("Draft")).toBeInTheDocument();
  });

  it("opens a version from its row", async () => {
    const user = userEvent.setup();
    stubServer();
    renderManager();
    const rows = await screen.findAllByRole("row");

    await user.click(within(rows[2]).getByText("2.0"));

    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("/admin/workflows/Workflows/review/2-0"));
  });

  it("lists the versions as cards on a phone", async () => {
    fakeNarrowScreen();
    stubServer();

    renderManager();

    expect(await screen.findByText("Version 1.0")).toBeInTheDocument();
    expect(screen.getByText("The initial cut")).toBeInTheDocument();
    expect(screen.getByText("Version 2.0")).toBeInTheDocument();
  });

  it("names a version by its node name when it carries no label", async () => {
    // A version whose label never made it into the repository still has to be identifiable
    stubServer({
      "jcr:primaryType": "wf:WorkflowDefinition",
      "title": "Standard review",
      "unlabelled": { "jcr:primaryType": "wf:WorkflowVersion", "tags": ["draft"] },
    });

    renderManager();

    const rows = await screen.findAllByRole("row");
    expect(within(rows[1]).getByText("unlabelled")).toBeInTheDocument();
  });

  it("says so when the workflow has no versions yet", async () => {
    stubServer({ "jcr:primaryType": "wf:WorkflowDefinition", title: "Empty" });

    renderManager();

    expect(await screen.findByText("This workflow has no versions yet.")).toBeInTheDocument();
  });

  it("renders the contributed actions against each version", async () => {
    // The buttons are not written into this page: a module adds one by shipping an extension, which
    // is what makes a later action possible without touching the manager
    mockedLoadExtensions.mockImplementation(point =>
      Promise.resolve(point === "WorkflowVersionActions" ? [ { "ext:render": labellingAction } ] : []));
    stubServer();

    renderManager();

    expect(await screen.findByText("acts on 1.0")).toBeInTheDocument();
    expect(screen.getByText("acts on 2.0")).toBeInTheDocument();
    expect(screen.getByText("acts on 3.0")).toBeInTheDocument();
    expect(mockedLoadExtensions).toHaveBeenCalledWith("WorkflowVersionActions");
  });

  it("renders the workflow's own contributed actions beside its title", async () => {
    mockedLoadExtensions.mockImplementation(point => Promise.resolve(point === "WorkflowActions"
      ? [ { "ext:render": ({ workflow }: WorkflowActionProps) => <span>{`acts on ${workflow.title}`}</span> } ]
      : []));
    stubServer();

    renderManager();

    expect(await screen.findByText("acts on Standard review")).toBeInTheDocument();
  });

  it("leads back to its homepage once an action takes the workflow away", async () => {
    const user = userEvent.setup();
    const removingAction = ({ removed }: WorkflowActionProps) => (
      <button type="button" onClick={removed}>remove</button>
    );
    mockedLoadExtensions.mockImplementation(point =>
      Promise.resolve(point === "WorkflowActions" ? [ { "ext:render": removingAction } ] : []));
    stubServer();
    renderManager();

    await user.click(await screen.findByRole("button", { name: "remove" }));

    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("/admin/workflows/Workflows"));
  });

  it("reads the workflow again when an action asks", async () => {
    // Only the page can refresh the listing an action changed
    const user = userEvent.setup();
    const reloadingAction = ({ reload }: WorkflowVersionActionProps) => (
      <button type="button" onClick={reload}>reload</button>
    );
    mockedLoadExtensions.mockResolvedValue([ { "ext:render": reloadingAction } ]);
    const fetchMock = stubServer();
    renderManager();
    const buttons = await screen.findAllByRole("button", { name: "reload" });
    const listingsBefore = fetchMock.mock.calls.length;

    await user.click(buttons[0]);

    await waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThan(listingsBefore));
  });

  it("reports a load failure, and says why", async () => {
    stubRefusal(500);

    renderManager();

    const report = await screen.findByRole("alert");
    expect(report).toHaveTextContent("This workflow could not be loaded");
    expect(report).toHaveTextContent("(HTTP 500)");
  });

  it("keeps the workflow on its page when reading it again fails, and says so", async () => {
    // What is on the page is still what was last read; a refusal to read it again is reported above it
    // rather than taking its place
    const user = userEvent.setup();
    const reloadingAction = ({ reload }: WorkflowVersionActionProps) => (
      <button type="button" onClick={reload}>reload</button>
    );
    mockedLoadExtensions.mockResolvedValue([ { "ext:render": reloadingAction } ]);
    stubServer();
    renderManager();
    const buttons = await screen.findAllByRole("button", { name: "reload" });

    stubRefusal(500);
    await user.click(buttons[0]);

    expect(await screen.findByRole("alert")).toHaveTextContent("The workflow could not be reloaded");
    expect(screen.getByRole("heading", { name: "Standard review" })).toBeInTheDocument();
    expect(screen.getAllByRole("row")).toHaveLength(4);
  });

  it("reloads when the load failure's Retry is used", async () => {
    const user = userEvent.setup();
    stubRefusal(500);
    renderManager();
    await screen.findByRole("alert");

    stubServer();
    await user.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByRole("heading", { name: "Standard review" })).toBeInTheDocument();
  });
});
