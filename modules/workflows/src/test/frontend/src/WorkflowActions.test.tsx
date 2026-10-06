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

import { type ReactNode } from "react";

import { ThemeProvider } from "@mui/material/styles";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";

import { clearActions } from "@iap/frontend-commons/actionsManager";
import { appTheme } from "@iap/frontend-commons/appTheme";
import { NoticeProvider } from "@iap/frontend-commons/components/NoticeSnackbar";
import { SESSION_INFO_URL } from "@iap/frontend-commons/reLogin";
import { loadExtensions } from "@iap/ui-extension/extensionManager";
import WorkflowActions, { type WorkflowActionProps } from "@iap/workflows/WorkflowActions";
import type { WorkflowSummary } from "@iap/workflows/workflowModel";
import WorkflowNewVersionAction from "@iap/workflows/WorkflowNewVersionAction";
import WorkflowPropertiesAction from "@iap/workflows/WorkflowPropertiesAction";

vi.mock("@iap/ui-extension/extensionManager", () => ({ loadExtensions: vi.fn() }));

const mockedLoadExtensions = vi.mocked(loadExtensions);

type FetchStub = (url: string, options?: RequestInit) => Promise<Response>;

const WORKFLOW_PATH = "/Workflows/review";

const version = (label: string) => ({
  name: label.replace(".", "-"),
  path: `${WORKFLOW_PATH}/${label.replace(".", "-")}`,
  version: label,
  description: "",
  tags: [ "retired" ],
  lastModified: "",
  events: [],
});

const workflow = (events: string[] = [ "createVersion", "save" ]): WorkflowSummary => ({
  path: WORKFLOW_PATH,
  name: "review",
  title: "Standard review",
  active: true,
  retired: false,
  created: "",
  lastModified: "",
  events,
  versions: [ version("1.0"), version("2.0"), version("3.0") ],
});

// An engine that runs every event it is given. One that created something answers with a redirect to
// it, which fetch follows on its own, so the final URL is where the caller reads the new path from.
const stubFetch = () => {
  const fetchMock = vi.fn<FetchStub>((url, options) => Promise.resolve({
    ok: true,
    status: 200,
    redirected: options?.method === "POST",
    url: `http://localhost${url.split(".")[0]}/created`,
    headers: new Headers(),
    json: () => Promise.resolve({}),
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
      json: () => Promise.resolve({ error: `The engine refused this (${status})` }),
    }) as unknown as Response)));
};

// Wherever the router ended up, as text.
function Destination() {
  const { pathname } = useLocation();
  return <div>{`went to ${pathname}`}</div>;
}

// Renders an action inside a router that displays wherever it navigates to.
const renderAction = (Action: (props: WorkflowActionProps) => ReactNode, props: WorkflowActionProps) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <MemoryRouter initialEntries={[`/admin/workflows${WORKFLOW_PATH}`]}>
      <Routes>
        <Route path={`/admin/workflows${WORKFLOW_PATH}`} element={<Action {...props} />} />
        <Route path="*" element={<Destination />} />
      </Routes>
    </MemoryRouter>
  </ThemeProvider>,
  { wrapper: NoticeProvider },
);

beforeEach(() => {
  clearActions();
  mockedLoadExtensions.mockResolvedValue([]);
});

afterEach(() => vi.unstubAllGlobals());

describe("every workflow action", () => {
  it.each([
    [ "Edit properties", WorkflowPropertiesAction, "save" ],
    [ "New version", WorkflowNewVersionAction, "createVersion" ],
  ] as const)("offers %s exactly where the server offers its event", (name, Action, event) => {
    const { unmount } = renderAction(Action, { workflow: workflow([ event ]), reload: vi.fn() });
    expect(screen.getByRole("button", { name })).toBeInTheDocument();
    unmount();

    renderAction(Action, { workflow: workflow([]), reload: vi.fn() });
    expect(screen.queryByRole("button", { name })).not.toBeInTheDocument();
  });
});

describe("WorkflowActions", () => {
  it("renders the actions contributed on its point, in the order the repository lists them", async () => {
    mockedLoadExtensions.mockResolvedValue([
      { "ext:render": ({ workflow: shown }: WorkflowActionProps) => <span>{`first on ${shown.title}`}</span> },
      { "ext:render": () => <span>second action</span> },
    ]);

    render(<WorkflowActions workflow={workflow()} reload={vi.fn()} />);

    expect(await screen.findByText("first on Standard review")).toBeInTheDocument();
    expect(screen.getByText("second action")).toBeInTheDocument();
    expect(mockedLoadExtensions).toHaveBeenCalledWith("WorkflowActions");
  });
});

describe("the properties action", () => {
  it("saves an edit of the workflow's properties, and has the page read it again", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch();
    const reload = vi.fn();
    renderAction(WorkflowPropertiesAction, { workflow: workflow(), reload });

    await user.click(screen.getByRole("button", { name: "Edit properties" }));
    const dialog = await screen.findByRole("dialog", { name: "Workflow properties" });
    await user.clear(within(dialog).getByRole("textbox", { name: /Title/ }));
    await user.type(within(dialog).getByRole("textbox", { name: /Title/ }), "Reviewed twice");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const save = fetchMock.mock.calls.find(call => call[1]?.method === "POST");
    expect(save?.[0]).toBe(`${WORKFLOW_PATH}.save.json`);
    // The title and nothing else: whether the workflow runs is read off its versions
    expect(Object.fromEntries((save?.[1]?.body as URLSearchParams).entries())).toEqual({
      title: "Reviewed twice",
    });
    expect(reload).toHaveBeenCalled();
  });

  it("keeps the dialog open and says why when the save is refused", async () => {
    const user = userEvent.setup();
    renderAction(WorkflowPropertiesAction, { workflow: workflow(), reload: vi.fn() });
    await user.click(screen.getByRole("button", { name: "Edit properties" }));
    const dialog = await screen.findByRole("dialog", { name: "Workflow properties" });

    stubFailingFetch(403);
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("The engine refused this (403)");
    expect(screen.getByRole("dialog", { name: "Workflow properties" })).toBeInTheDocument();
  });
});

describe("the new-version action", () => {
  it("suggests the number after the largest a version is named with as the label", async () => {
    const user = userEvent.setup();
    renderAction(WorkflowNewVersionAction, { workflow: workflow(), reload: vi.fn() });

    await user.click(screen.getByRole("button", { name: "New version" }));
    const dialog = await screen.findByRole("dialog", { name: /New version/ });

    // After 1-0, 2-0 and 3-0; a suggestion only, which the user may replace with any label
    expect(within(dialog).getByRole("textbox", { name: /Version/ })).toHaveValue("4.0");
  });

  it("creates a version and opens its editor", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch();
    renderAction(WorkflowNewVersionAction, { workflow: workflow(), reload: vi.fn() });

    await user.click(screen.getByRole("button", { name: "New version" }));
    const dialog = await screen.findByRole("dialog", { name: /New version/ });
    await user.clear(within(dialog).getByRole("textbox", { name: /Version/ }));
    await user.type(within(dialog).getByRole("textbox", { name: /Version/ }), "4.0");
    await user.type(within(dialog).getByRole("textbox", { name: /Description/ }), "With an escalation");
    await user.click(within(dialog).getByRole("button", { name: "Create" }));

    // Straight into the editor: a version that was just opened exists to be drawn
    expect(await screen.findByText(`went to /admin/workflows${WORKFLOW_PATH}/created.edit`)).toBeInTheDocument();
    const create = fetchMock.mock.calls.find(call => call[0] === `${WORKFLOW_PATH}.createVersion.json`);
    const body = create?.[1]?.body as FormData;
    expect(body.get("version")).toBe("4.0");
    expect(body.get("description")).toBe("With an escalation");
    // The diagram travels with the request; where the version starts in its lifecycle is the definition's
    expect(body.get("bpmn.xml")).toBeInstanceOf(File);
    expect(body.get("tags")).toBeNull();
  });

  it("keeps the dialog open and says why when the creation is refused", async () => {
    const user = userEvent.setup();
    renderAction(WorkflowNewVersionAction, { workflow: workflow(), reload: vi.fn() });
    await user.click(screen.getByRole("button", { name: "New version" }));
    const dialog = await screen.findByRole("dialog", { name: /New version/ });

    stubFailingFetch(500);
    await user.click(within(dialog).getByRole("button", { name: "Create" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent("The engine refused this (500)");
  });

  it("refuses a version label the workflow already uses", async () => {
    const user = userEvent.setup();
    renderAction(WorkflowNewVersionAction, { workflow: workflow(), reload: vi.fn() });

    await user.click(screen.getByRole("button", { name: "New version" }));
    const dialog = await screen.findByRole("dialog", { name: /New version/ });
    await user.clear(within(dialog).getByRole("textbox", { name: /Version/ }));
    await user.type(within(dialog).getByRole("textbox", { name: /Version/ }), "2.0");

    expect(within(dialog).getByText("This workflow already has a version with that label")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Create" })).toBeDisabled();
  });

  it("can be abandoned without creating anything", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch();
    renderAction(WorkflowNewVersionAction, { workflow: workflow(), reload: vi.fn() });
    await user.click(screen.getByRole("button", { name: "New version" }));
    const dialog = await screen.findByRole("dialog", { name: /New version/ });

    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(fetchMock.mock.calls.filter(call => call[1]?.method === "POST")).toEqual([]);
  });
});
