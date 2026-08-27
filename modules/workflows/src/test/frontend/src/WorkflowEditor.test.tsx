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
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useLocation } from "react-router";

import { appTheme } from "@iap/frontend-commons/appTheme";
import { SESSION_INFO_URL } from "@iap/frontend-commons/reLogin";
import WorkflowEditor from "@iap/workflows/WorkflowEditor";

// The canvas is covered by its own suite; here it just reports itself ready and serializes a known
// diagram. That keeps these tests about the page around it -- what's open, and the save.
const { canvasProps } = vi.hoisted(() => ({ canvasProps: [] as Record<string, unknown>[] }));

vi.mock("@iap/workflows/BpmnEditor", () => ({
  default: (props: Record<string, unknown>) => {
    canvasProps.push(props);
    return (
      <div data-testid="bpmn-canvas">
        <button
          type="button"
          onClick={() => (props.onDirtyChange as (dirty: boolean) => void)(true)}
        >
          pretend to draw
        </button>
      </div>
    );
  },
}));

// A stubbed fetch: the URL, and the request options a write carries.
type FetchStub = (url: string, options?: RequestInit) => Promise<Response>;

const VERSION_PATH = "/Workflows/review/2-0";

const definition = {
  "jcr:primaryType": "wf:WorkflowDefinition",
  "title": "Standard review",
  "1-0": {
    "jcr:primaryType": "wf:WorkflowVersion",
    "version": "1.0",
    "state": "ACTIVE",
  },
  "2-0": {
    "jcr:primaryType": "wf:WorkflowVersion",
    "version": "2.0",
    "description": "With an escalation",
    "state": "DRAFT",
  },
};

const stubFetch = (body: unknown = definition) => {
  const fetchMock = vi.fn<FetchStub>((url) => Promise.resolve({
    ok: true, status: 200, url, headers: new Headers(), json: () => Promise.resolve(body),
  } as unknown as Response));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
};

// A server that refuses everything but confirms the session is live, so a failure reads as the
// server's own rather than as a lapsed session.
const stubFailingFetch = (status: number) => {
  vi.stubGlobal("fetch", vi.fn<FetchStub>(url => Promise.resolve((url === SESSION_INFO_URL
    ? { ok: true, status: 200, url, json: () => Promise.resolve({ userID: "admin" }) }
    : { ok: false, status, statusText: "Refused", url }) as unknown as Response)));
};

// Wherever the router stands, query and all: which page a version is opened on is asked for there,
// so a URL without it would not say whether the editor or the viewer was arrived at.
function CurrentUrl() {
  const { pathname, search } = useLocation();
  return <div data-testid="url">{`${pathname}${search}`}</div>;
}

// Renders the page the way the console does: the version's path and whether the URL asked to edit it
// are worked out from the URL there and handed over, so this page is a function of the two.
const renderEditor = (options: { edit?: boolean; path?: string } = {}) => {
  const { edit = false, path = VERSION_PATH } = options;
  const url = `/admin/workflows${path}${edit ? "?page=edit" : ""}`;
  return render(
    <ThemeProvider theme={appTheme} defaultMode="light">
      <MemoryRouter initialEntries={[url]}>
        <WorkflowEditor path={path} editing={edit} />
        <CurrentUrl />
      </MemoryRouter>
    </ThemeProvider>
  );
};

// Where the router ended up.
const currentUrl = () => screen.getByTestId("url").textContent;

// The canvas's props as of its latest render.
const latestCanvas = () => canvasProps.at(-1) ?? {};

beforeEach(() => {
  canvasProps.length = 0;
});

afterEach(() => vi.unstubAllGlobals());

describe("WorkflowEditor", () => {
  it("names the version it has open, with its state", async () => {
    stubFetch();

    renderEditor();

    expect(await screen.findByRole("heading", { name: "Standard review: Version 2.0" })).toBeInTheDocument();
    expect(screen.getByText("Draft")).toBeInTheDocument();
    expect(screen.getByText("With an escalation")).toBeInTheDocument();
    // No link of its own back to the workflow: the shell's breadcrumb trail is the way back
    expect(screen.queryByRole("link", { name: /Standard review/ })).not.toBeInTheDocument();
  });

  it("offers to edit a draft it is only showing, at the same URL asked the other way", async () => {
    // The editor is this version opened for editing, not a page below it -- the same move the
    // workflow's own listing offers.
    stubFetch();

    renderEditor();

    expect(await screen.findByRole("link", { name: "Edit" }))
      .toHaveAttribute("href", "/admin/workflows/Workflows/review/2-0?page=edit");
  });

  it("offers no editing of a version no longer a draft", async () => {
    // Whatever is being followed is not changed from here, so there is nothing to offer
    stubFetch();

    renderEditor({ path: "/Workflows/review/1-0" });

    await screen.findByRole("heading", { name: /Standard review/ });
    expect(screen.queryByRole("link", { name: "Edit" })).not.toBeInTheDocument();
  });

  it("does not offer to edit what is already open for editing", async () => {
    stubFetch();

    renderEditor({ edit: true });

    await screen.findByRole("heading", { name: /Standard review/ });
    expect(screen.queryByRole("link", { name: "Edit" })).not.toBeInTheDocument();
  });

  it("offers no way to write anything in view mode", async () => {
    stubFetch();

    renderEditor();

    await screen.findByRole("heading", { name: /Standard review/ });
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
    // The canvas is asked for a viewer, and is not offered anything to serialize into
    expect(latestCanvas().editable).toBe(false);
  });

  it("saves the diagram in edit mode, and stops saying there are unsaved changes", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch();
    renderEditor({ edit: true });
    await screen.findByRole("heading", { name: /Standard review/ });
    // The page is handed the means to serialize once the canvas is ready
    (latestCanvas().onReady as (serialize: () => Promise<string>) => void)(() => Promise.resolve("<drawn/>"));

    await user.click(screen.getByRole("button", { name: "pretend to draw" }));
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(screen.queryByText("Unsaved changes")).not.toBeInTheDocument());
    const save = fetchMock.mock.calls.find(call => call[1]?.method === "POST");
    expect(save?.[0]).toBe(VERSION_PATH);
    // The diagram is a plain payload part of the save request, named after the file it becomes.
    expect((save?.[1]?.body as FormData).get("bpmn.xml")).toBeInstanceOf(File);
    expect(await screen.findByText("The diagram was saved")).toBeInTheDocument();

    // And the confirmation can be dismissed, being a report of something already done
    await user.click(screen.getByRole("button", { name: "Dismiss" }));
    await waitFor(() => expect(screen.queryByText("The diagram was saved")).not.toBeInTheDocument());
  });

  it("saves and then shows the version, on the same URL without the editor asked for", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch();
    renderEditor({ edit: true });
    await screen.findByRole("heading", { name: /Standard review/ });
    (latestCanvas().onReady as (serialize: () => Promise<string>) => void)(() => Promise.resolve("<drawn/>"));
    await user.click(screen.getByRole("button", { name: "pretend to draw" }));

    await user.click(screen.getByRole("button", { name: "Save and view" }));

    await waitFor(() => expect(currentUrl()).toBe("/admin/workflows/Workflows/review/2-0"));
    expect(fetchMock.mock.calls.filter(call => call[1]?.method === "POST")).toHaveLength(1);
  });

  it("saves and then closes onto the workflow the version belongs to", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch();
    renderEditor({ edit: true });
    await screen.findByRole("heading", { name: /Standard review/ });
    (latestCanvas().onReady as (serialize: () => Promise<string>) => void)(() => Promise.resolve("<drawn/>"));
    await user.click(screen.getByRole("button", { name: "pretend to draw" }));

    await user.click(screen.getByRole("button", { name: "Save and close" }));

    await waitFor(() => expect(currentUrl()).toBe("/admin/workflows/Workflows/review"));
    expect(fetchMock.mock.calls.filter(call => call[1]?.method === "POST")).toHaveLength(1);
  });

  it("stays where the unsaved diagram is when a save on its way somewhere is refused", async () => {
    // Leaving would take the only copy of what was drawn with it, so a refusal is reported here and
    // offered again rather than navigated past
    const user = userEvent.setup();
    stubFetch();
    renderEditor({ edit: true });
    await screen.findByRole("heading", { name: /Standard review/ });
    (latestCanvas().onReady as (serialize: () => Promise<string>) => void)(() => Promise.resolve("<drawn/>"));

    stubFailingFetch(403);
    await user.click(screen.getByRole("button", { name: "Save and close" }));

    expect(await screen.findByText("The diagram could not be saved")).toBeInTheDocument();
    expect(currentUrl()).toBe("/admin/workflows/Workflows/review/2-0?page=edit");
  });

  it("has the browser warn before a page with unsaved changes is left", async () => {
    // The page's own header says the same thing, but only the browser's warning can interrupt a
    // reload or a closed tab, and it only appears for a cancelled beforeunload
    const user = userEvent.setup();
    stubFetch();
    renderEditor({ edit: true });
    await screen.findByRole("heading", { name: /Standard review/ });

    const clean = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(clean);
    expect(clean.defaultPrevented).toBe(false);

    await user.click(screen.getByRole("button", { name: "pretend to draw" }));
    const dirty = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(dirty);

    expect(dirty.defaultPrevented).toBe(true);
  });

  it("reports a save the server refused, and offers to try again", async () => {
    const user = userEvent.setup();
    stubFetch();
    renderEditor({ edit: true });
    await screen.findByRole("heading", { name: /Standard review/ });
    (latestCanvas().onReady as (serialize: () => Promise<string>) => void)(() => Promise.resolve("<drawn/>"));

    stubFailingFetch(403);
    await user.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText("The diagram could not be saved")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });

  it("does nothing on Save until the canvas is ready to serialize", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch();
    renderEditor({ edit: true });
    await screen.findByRole("heading", { name: /Standard review/ });

    await user.click(screen.getByRole("button", { name: "Save" }));

    expect(fetchMock.mock.calls.filter(call => call[1]?.method === "POST")).toEqual([]);
  });

  it("shows an active version read-only even when asked to edit it, and says why", async () => {
    // The URL can always be typed; what a version's state allows is not the URL's decision
    stubFetch();

    renderEditor({ edit: true, path: "/Workflows/review/1-0" });

    expect(await screen.findByText(/Only a draft can be edited/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
    expect(latestCanvas().editable).toBe(false);
  });

  it("says as much for a retired version asked to be edited", async () => {
    stubFetch({
      "jcr:primaryType": "wf:WorkflowDefinition",
      "title": "Standard review",
      "1-0": { "jcr:primaryType": "wf:WorkflowVersion", "version": "1.0", "state": "RETIRED" },
    });

    renderEditor({ edit: true, path: "/Workflows/review/1-0" });

    expect(await screen.findByText(/Version 1.0 is retired/)).toBeInTheDocument();
  });

  it("shows a version on trial read-only, and says how to change it", async () => {
    // A trial is tried as it stands; what it is changed by is being returned to a draft, which is
    // the one thing this notice can usefully point at
    stubFetch({
      "jcr:primaryType": "wf:WorkflowDefinition",
      "title": "Standard review",
      "1-0": { "jcr:primaryType": "wf:WorkflowVersion", "version": "1.0", "state": "TRIAL" },
    });

    renderEditor({ edit: true, path: "/Workflows/review/1-0" });

    expect(await screen.findByText(/Version 1.0 is on trial/)).toBeInTheDocument();
    expect(screen.getByText(/return it to being a draft/)).toBeInTheDocument();
    expect(latestCanvas().editable).toBe(false);
  });

  it("names a version with no label of its own by its node name", async () => {
    stubFetch({
      "jcr:primaryType": "wf:WorkflowDefinition",
      "title": "Standard review",
      "2-0": { "jcr:primaryType": "wf:WorkflowVersion", "state": "DRAFT" },
    });

    renderEditor();

    expect(await screen.findByRole("heading", { name: "Standard review: Version 2-0" })).toBeInTheDocument();
  });

  it("displays whatever is at the path when the workflow has no such version", async () => {
    stubFetch();

    renderEditor({ path: "/Workflows/review/9-9" });

    expect(await screen.findByText(/has no version stored at \/Workflows\/review\/9-9/)).toBeInTheDocument();
    expect(screen.getByTestId("bpmn-canvas")).toBeInTheDocument();
  });

  it("reports a workflow it could not read, and retries on request", async () => {
    const user = userEvent.setup();
    stubFailingFetch(500);
    renderEditor();
    const report = await screen.findByRole("alert");
    expect(report).toHaveTextContent("This workflow version could not be loaded");

    stubFetch();
    await user.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByRole("heading", { name: /Standard review/ })).toBeInTheDocument();
  });
});
