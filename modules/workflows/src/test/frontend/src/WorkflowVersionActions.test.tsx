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
import { SESSION_INFO_URL } from "@iap/frontend-commons/reLogin";
import { loadExtensions } from "@iap/ui-extension/extensionManager";
import type { WorkflowState, WorkflowSummary, WorkflowVersionSummary } from "@iap/workflows/workflowModel";
import WorkflowVersionActions, { type WorkflowVersionActionProps } from "@iap/workflows/WorkflowVersionActions";
import WorkflowVersionActivateAction from "@iap/workflows/WorkflowVersionActivateAction";
import WorkflowVersionDraftAction from "@iap/workflows/WorkflowVersionDraftAction";
import WorkflowVersionEditAction from "@iap/workflows/WorkflowVersionEditAction";
import WorkflowVersionRedraftAction from "@iap/workflows/WorkflowVersionRedraftAction";
import WorkflowVersionTrialAction from "@iap/workflows/WorkflowVersionTrialAction";
import WorkflowVersionViewAction from "@iap/workflows/WorkflowVersionViewAction";

vi.mock("@iap/ui-extension/extensionManager", () => ({ loadExtensions: vi.fn() }));

const mockedLoadExtensions = vi.mocked(loadExtensions);

// A stubbed fetch: the URL, and the request options a write carries.
type FetchStub = (url: string, options?: RequestInit) => Promise<Response>;

const version = (label: string, state: WorkflowState): WorkflowVersionSummary => ({
  name: label.replace(".", "-"),
  path: `/Workflows/review/${label.replace(".", "-")}`,
  version: label,
  description: "",
  state,
  lastModified: "",
});

const workflow = (...versions: WorkflowVersionSummary[]): WorkflowSummary => ({
  path: "/Workflows/review",
  name: "review",
  title: "Standard review",
  active: true,
  created: "",
  lastModified: "",
  versions,
});

// The props every action receives, with the two callbacks watchable.
const propsFor = (target: WorkflowVersionSummary, host: WorkflowSummary) => ({
  version: target,
  workflow: host,
  reload: vi.fn(),
  report: vi.fn(),
});

// Wherever the router ended up, as text: a navigation is then asserted on as the destination the
// user arrives at rather than as a call that was made.
function Destination() {
  // Query and all: which page a version is opened on is asked for there, so a destination without it
  // would not say which one the user arrived at
  const { pathname, search } = useLocation();
  return <div>{`went to ${pathname}${search}`}</div>;
}

// Renders an action inside a router that displays wherever it navigates to.
const renderAction = (
  Action: (props: WorkflowVersionActionProps) => ReactNode,
  props: WorkflowVersionActionProps,
) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <MemoryRouter initialEntries={["/admin/workflows/Workflows/review"]}>
      <Routes>
        <Route path="/admin/workflows/Workflows/review" element={<Action {...props} />} />
        <Route path="*" element={<Destination />} />
      </Routes>
    </MemoryRouter>
  </ThemeProvider>
);

// An engine that runs every event it is given. One that created something answers with a redirect to
// it, which fetch follows on its own, so the final URL is where the caller reads the new path from.
const stubFetch = (created?: string) => {
  const fetchMock = vi.fn<FetchStub>((url) => Promise.resolve({
    ok: true,
    status: 200,
    redirected: created !== undefined,
    url: `http://localhost${created ?? url}`,
    headers: new Headers(),
    json: () => Promise.resolve({}),
  } as unknown as Response));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
};

// An engine that refused the event, in the words it refused it with.
const stubRefusingFetch = (status: number, error: string) => {
  vi.stubGlobal("fetch", vi.fn<FetchStub>(url => Promise.resolve(((url === SESSION_INFO_URL
    ? { ok: true, status: 200, url, json: () => Promise.resolve({ userID: "admin" }) }
    : { ok: false, status, url, headers: new Headers(), json: () => Promise.resolve({ error }) })
  ) as unknown as Response)));
};

// The move the latest lifecycle event asked for, which is the event's own name.
const moveAskedFor = (fetchMock: { mock: { calls: [string, RequestInit?][] } }) => {
  const call = fetchMock.mock.calls.filter(([url]) => url.startsWith("/Workflows/")).at(-1);
  return call?.[0].split(".")[1];
};

beforeEach(() => {
  clearActions();
  mockedLoadExtensions.mockResolvedValue([]);
});

afterEach(() => vi.unstubAllGlobals());

describe("WorkflowVersionActions", () => {
  it("renders the contributed actions, in the order the repository lists them", async () => {
    const first = () => <span>first action</span>;
    const second = () => <span>second action</span>;
    mockedLoadExtensions.mockResolvedValue([
      { "ext:render": first },
      { "ext:render": second },
    ]);
    const draft = version("1.0", "DRAFT");

    render(<WorkflowVersionActions {...propsFor(draft, workflow(draft))} />);

    expect(await screen.findByText("first action")).toBeInTheDocument();
    const rendered = screen.getAllByText(/action$/).map(node => node.textContent);
    expect(rendered).toEqual(["first action", "second action"]);
  });

  it("stops caring about the actions it asked for once it is gone", async () => {
    // The extension point resolves after the page has moved on; nothing is set on a component that
    // no longer exists
    let resolveActions: (extensions: Record<string, unknown>[]) => void = () => undefined;
    mockedLoadExtensions.mockReturnValue(new Promise<Record<string, unknown>[]>(resolve => {
      resolveActions = resolve;
    }));
    const draft = version("1.0", "DRAFT");
    const { unmount } = render(<WorkflowVersionActions {...propsFor(draft, workflow(draft))} />);

    unmount();
    resolveActions([ { "ext:render": () => <span>late action</span> } ]);

    await waitFor(() => expect(screen.queryByText("late action")).not.toBeInTheDocument());
  });

  it("renders nothing at all when no action applies", async () => {
    const draft = version("1.0", "DRAFT");

    const { container } = render(<WorkflowVersionActions {...propsFor(draft, workflow(draft))} />);

    await waitFor(() => expect(mockedLoadExtensions).toHaveBeenCalled());
    expect(container).not.toHaveTextContent(/\w/);
  });
});

describe("the view action", () => {
  it("links to the version's own page, whatever state it is in", () => {
    for (const state of [ "DRAFT", "TRIAL", "ACTIVE", "RETIRED" ] as WorkflowState[]) {
      const target = version("1.0", state);
      const { unmount } = renderAction(WorkflowVersionViewAction, propsFor(target, workflow(target)));

      expect(screen.getByRole("link", { name: "View" }))
        .toHaveAttribute("href", "/admin/workflows/Workflows/review/1-0");
      unmount();
    }
  });
});

describe("the edit action", () => {
  it("links a draft to the editing mode of its page", () => {
    const draft = version("1.0", "DRAFT");

    renderAction(WorkflowVersionEditAction, propsFor(draft, workflow(draft)));

    expect(screen.getByRole("link", { name: "Edit" }))
      .toHaveAttribute("href", "/admin/workflows/Workflows/review/1-0?page=edit");
  });

  it("is not offered for a version that is no longer a draft", () => {
    // Editing an active or retired version would change a process out from under the things
    // executing it, and a trial is being tried as it stands; carrying any of them forward means
    // drafting — a copy of the first two, the trial itself
    for (const state of [ "TRIAL", "ACTIVE", "RETIRED" ] as WorkflowState[]) {
      const target = version("1.0", state);
      const { unmount } = renderAction(WorkflowVersionEditAction, propsFor(target, workflow(target)));

      expect(screen.queryByRole("link", { name: "Edit" })).not.toBeInTheDocument();
      unmount();
    }
  });
});

describe("the activate action", () => {
  it("names the version that will be retired, and promotes on confirmation", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch();
    const active = version("1.0", "ACTIVE");
    const draft = version("2.0", "DRAFT");
    const props = propsFor(draft, workflow(active, draft));
    renderAction(WorkflowVersionActivateAction, props);

    await user.click(screen.getByRole("button", { name: "Activate" }));
    const dialog = await screen.findByRole("dialog");
    // The retirement is the part that cannot be seen from the row the button sits in
    expect(dialog).toHaveTextContent("Version 1.0 is retired in the same step");
    await user.click(within(dialog).getByRole("button", { name: "Activate" }));

    await waitFor(() => expect(props.reload).toHaveBeenCalled());
    expect(fetchMock).toHaveBeenCalledWith("/Workflows/review/2-0.activate.json",
      expect.objectContaining({ method: "POST" }));
    expect(moveAskedFor(fetchMock)).toBe("activate");
    expect(props.report).toHaveBeenCalledWith("Version 2.0 is now the active version of Standard review");
  });

  it("says that nothing is retired when the workflow has no active version", async () => {
    const user = userEvent.setup();
    stubFetch();
    const draft = version("1.0", "DRAFT");
    renderAction(WorkflowVersionActivateAction, propsFor(draft, workflow(draft)));

    await user.click(screen.getByRole("button", { name: "Activate" }));

    expect(await screen.findByRole("dialog")).toHaveTextContent("no active version at the moment");
  });

  it("keeps the confirmation open and reports what the server refused", async () => {
    const user = userEvent.setup();
    stubRefusingFetch(409, "Only a draft version can be activated");
    const draft = version("1.0", "DRAFT");
    const props = propsFor(draft, workflow(draft));
    renderAction(WorkflowVersionActivateAction, props);

    await user.click(screen.getByRole("button", { name: "Activate" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Activate" }));

    expect(await within(dialog).findByText("Only a draft version can be activated")).toBeInTheDocument();
    expect(props.reload).not.toHaveBeenCalled();
  });

  it("names an unlabelled version, and the unlabelled one it supersedes, by node name", async () => {
    const user = userEvent.setup();
    stubFetch();
    const active = { ...version("1.0", "ACTIVE"), version: "" };
    const draft = { ...version("2.0", "DRAFT"), version: "" };
    renderAction(WorkflowVersionActivateAction, propsFor(draft, workflow(active, draft)));

    await user.click(screen.getByRole("button", { name: "Activate" }));

    const dialog = await screen.findByRole("dialog");
    expect(dialog).toHaveAccessibleName("Activate version 2-0?");
    expect(dialog).toHaveTextContent("Version 1-0 is retired in the same step");
  });

  it("promotes a version that has been on trial", async () => {
    // A trial is exactly a version being considered for this, so it is promoted the same way a draft
    // is — and supersedes what was running, which the confirmation still has to say
    const user = userEvent.setup();
    const fetchMock = stubFetch();
    const active = version("1.0", "ACTIVE");
    const trial = version("2.0", "TRIAL");
    const props = propsFor(trial, workflow(active, trial));
    renderAction(WorkflowVersionActivateAction, props);

    await user.click(screen.getByRole("button", { name: "Activate" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Activate" }));

    await waitFor(() => expect(props.reload).toHaveBeenCalled());
    expect(fetchMock).toHaveBeenCalledWith("/Workflows/review/2-0.activate.json",
      expect.objectContaining({ method: "POST" }));
    expect(moveAskedFor(fetchMock)).toBe("activate");
  });

  it("is not offered for a version that is already active, or retired", () => {
    for (const state of [ "ACTIVE", "RETIRED" ] as WorkflowState[]) {
      const target = version("1.0", state);
      const { unmount } = renderAction(WorkflowVersionActivateAction, propsFor(target, workflow(target)));

      expect(screen.queryByRole("button", { name: "Activate" })).not.toBeInTheDocument();
      unmount();
    }
  });
});

describe("the trial action", () => {
  it("puts a draft on trial once the freeze is confirmed", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch();
    const draft = version("2.0", "DRAFT");
    const props = propsFor(draft, workflow(version("1.0", "ACTIVE"), draft));
    renderAction(WorkflowVersionTrialAction, props);

    await user.click(screen.getByRole("button", { name: "Start trial" }));
    const dialog = await screen.findByRole("dialog", { name: "Put version 2.0 on trial?" });
    // What the confirmation is for: the diagram stops being editable, and this is not an activation
    expect(dialog).toHaveTextContent("stops being editable");
    await user.click(within(dialog).getByRole("button", { name: "Start trial" }));

    await waitFor(() => expect(props.reload).toHaveBeenCalled());
    expect(fetchMock).toHaveBeenCalledWith("/Workflows/review/2-0.startTrial.json",
      expect.objectContaining({ method: "POST" }));
    expect(moveAskedFor(fetchMock)).toBe("startTrial");
    expect(props.report).toHaveBeenCalledWith("Version 2.0 of Standard review is on trial");
  });

  it("is offered for a draft only", () => {
    for (const state of [ "TRIAL", "ACTIVE", "RETIRED" ] as WorkflowState[]) {
      const target = version("1.0", state);
      const { unmount } = renderAction(WorkflowVersionTrialAction, propsFor(target, workflow(target)));

      expect(screen.queryByRole("button", { name: "Start trial" })).not.toBeInTheDocument();
      unmount();
    }
  });

  it("names a version without a label by its node name", async () => {
    // A version created without a label is still a version, and has to be nameable in the sentence
    // asking about it; its node name is the only other thing it is known by
    const user = userEvent.setup();
    stubFetch();
    const unlabelled = { ...version("2.0", "DRAFT"), version: "" };

    renderAction(WorkflowVersionTrialAction, propsFor(unlabelled, workflow(unlabelled)));
    await user.click(screen.getByRole("button", { name: "Start trial" }));

    expect(await screen.findByRole("dialog", { name: `Put version ${unlabelled.name} on trial?` }))
      .toBeInTheDocument();
  });
});

describe("the return-to-draft action", () => {
  it("takes a trial back to being a draft", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch();
    const trial = version("2.0", "TRIAL");
    const props = propsFor(trial, workflow(version("1.0", "ACTIVE"), trial));
    renderAction(WorkflowVersionRedraftAction, props);

    await user.click(screen.getByRole("button", { name: "Return to draft" }));
    const dialog = await screen.findByRole("dialog", { name: "Return version 2.0 to draft?" });
    // The active version is not disturbed by a trial ending, which is the thing worth reassuring about
    expect(dialog).toHaveTextContent("stays active");
    await user.click(within(dialog).getByRole("button", { name: "Return to draft" }));

    await waitFor(() => expect(props.reload).toHaveBeenCalled());
    expect(moveAskedFor(fetchMock)).toBe("returnToDraft");
    expect(props.report).toHaveBeenCalledWith("Version 2.0 of Standard review is a draft again");
  });

  it("is offered for a trial only", () => {
    // A draft is already one; an active or retired version has instances following it, and is
    // carried forward by drafting a copy instead
    for (const state of [ "DRAFT", "ACTIVE", "RETIRED" ] as WorkflowState[]) {
      const target = version("1.0", state);
      const { unmount } = renderAction(WorkflowVersionRedraftAction, propsFor(target, workflow(target)));

      expect(screen.queryByRole("button", { name: "Return to draft" })).not.toBeInTheDocument();
      unmount();
    }
  });

  it("names a version without a label by its node name", async () => {
    const user = userEvent.setup();
    stubFetch();
    const unlabelled = { ...version("2.0", "TRIAL"), version: "" };
    const props = propsFor(unlabelled, workflow(unlabelled));

    renderAction(WorkflowVersionRedraftAction, props);
    await user.click(screen.getByRole("button", { name: "Return to draft" }));
    const dialog = await screen.findByRole("dialog", { name: `Return version ${unlabelled.name} to draft?` });
    await user.click(within(dialog).getByRole("button", { name: "Return to draft" }));

    await waitFor(() => expect(props.report)
      .toHaveBeenCalledWith(`Version ${unlabelled.name} of Standard review is a draft again`));
  });
});

describe("the draft-from action", () => {
  it("copies an active version into a new draft and opens it", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch("/Workflows/review/2-0");
    const active = version("1.0", "ACTIVE");
    const props = propsFor(active, workflow(active));
    renderAction(WorkflowVersionDraftAction, props);

    await user.click(screen.getByRole("button", { name: "New draft from this" }));
    const dialog = await screen.findByRole("dialog", { name: /New draft from version 1.0/ });
    await user.type(within(dialog).getByRole("textbox", { name: /Version/ }), "2.0");
    await user.click(within(dialog).getByRole("button", { name: "Create draft" }));

    await waitFor(() => expect(props.reload).toHaveBeenCalled());
    expect(fetchMock).toHaveBeenCalledWith("/Workflows/review/1-0.draft.json",
      expect.objectContaining({ method: "POST" }));
    // Straight into the editor: a draft that was just copied exists to be changed
    expect(await screen.findByText("went to /admin/workflows/Workflows/review/2-0?page=edit")).toBeInTheDocument();
  });

  it("refuses a label the workflow already uses", async () => {
    const user = userEvent.setup();
    stubFetch();
    const active = version("1.0", "ACTIVE");
    const retired = version("0.9", "RETIRED");
    renderAction(WorkflowVersionDraftAction, propsFor(active, workflow(retired, active)));

    await user.click(screen.getByRole("button", { name: "New draft from this" }));
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByRole("textbox", { name: /Version/ }), "0.9");

    expect(within(dialog).getByText("This workflow already has a version with that label")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Create draft" })).toBeDisabled();
  });

  it("can be abandoned without creating anything", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch();
    const active = version("1.0", "ACTIVE");
    renderAction(WorkflowVersionDraftAction, propsFor(active, workflow(active)));
    await user.click(screen.getByRole("button", { name: "New draft from this" }));
    const dialog = await screen.findByRole("dialog");

    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(fetchMock.mock.calls.filter(call => call[1]?.method === "POST")).toEqual([]);
  });

  it("is not offered for a draft, which can simply be edited", () => {
    const draft = version("1.0", "DRAFT");

    renderAction(WorkflowVersionDraftAction, propsFor(draft, workflow(draft)));

    expect(screen.queryByRole("button", { name: "New draft from this" })).not.toBeInTheDocument();
  });

  it("is offered for a trial, which cannot be edited where it stands", () => {
    // Returning the trial to a draft changes the version being tried; branching leaves the trial
    // running and carries its diagram forward, which is a different thing to want
    const trial = version("1.0", "TRIAL");

    renderAction(WorkflowVersionDraftAction, propsFor(trial, workflow(trial)));

    expect(screen.getByRole("button", { name: "New draft from this" })).toBeInTheDocument();
  });

  it("names a source version without a label by its node name", async () => {
    const user = userEvent.setup();
    stubFetch();
    const unlabelled = { ...version("1.0", "ACTIVE"), version: "" };

    renderAction(WorkflowVersionDraftAction, propsFor(unlabelled, workflow(unlabelled)));
    await user.click(screen.getByRole("button", { name: "New draft from this" }));

    expect(await screen.findByRole("dialog", { name: `New draft from version ${unlabelled.name}` }))
      .toBeInTheDocument();
  });

  it("keeps the dialog open and says why when the copy is refused", async () => {
    // The label is the one thing the user can still change, so the refusal belongs beside the field
    // rather than replacing the dialog it was typed into
    const user = userEvent.setup();
    stubRefusingFetch(409, "A version with that label already exists");
    const active = version("1.0", "ACTIVE");
    const props = propsFor(active, workflow(active));
    renderAction(WorkflowVersionDraftAction, props);

    await user.click(screen.getByRole("button", { name: "New draft from this" }));
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByRole("textbox", { name: /Version/ }), "2.0");
    await user.click(within(dialog).getByRole("button", { name: "Create draft" }));

    expect(await within(dialog).findByText("A version with that label already exists")).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(props.reload).not.toHaveBeenCalled();
  });

  it("can be dismissed with the dialog's close button", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch();
    const active = version("1.0", "ACTIVE");
    renderAction(WorkflowVersionDraftAction, propsFor(active, workflow(active)));
    await user.click(screen.getByRole("button", { name: "New draft from this" }));
    const dialog = await screen.findByRole("dialog");

    await user.click(within(dialog).getByRole("button", { name: "close" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(fetchMock.mock.calls.filter(call => call[1]?.method === "POST")).toEqual([]);
  });
});
