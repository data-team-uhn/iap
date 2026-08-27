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

import { appTheme } from "@iap/frontend-commons/appTheme";
import { SESSION_INFO_URL } from "@iap/frontend-commons/reLogin";
import NewWorkflowDialog from "@iap/workflows/NewWorkflowDialog";
import { forgetWorkflowHomepages } from "@iap/workflows/workflowModel";

// The homepage discovery is kept for the life of the session, so each test starts from an unasked one
beforeEach(forgetWorkflowHomepages);

const homepages = [
  { path: "/Workflows", title: "Workflows" },
  { path: "/SystemWorkflows", title: "System workflows" },
];

type FetchStub = (url: string, options?: RequestInit) => Promise<Response>;

const stubFetch = () => {
  const fetchMock = vi.fn<FetchStub>(url => Promise.resolve({
    ok: true,
    status: 200,
    // The engine answers a creation with a redirect to what it created, which fetch follows on its
    // own, so the final URL is where the caller reads the new path from
    redirected: true,
    url: `http://localhost${url.split(".")[0]}/created`,
    headers: new Headers(),
    json: () => Promise.resolve({}),
  } as unknown as Response));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
};

// A server that refuses the creation while reporting the session as live.
const stubFailingFetch = () => {
  vi.stubGlobal("fetch", vi.fn<FetchStub>(url => Promise.resolve((url === SESSION_INFO_URL
    ? { ok: true, status: 200, url, json: () => Promise.resolve({ userID: "admin" }) }
    : {
      ok: false,
      status: 403,
      statusText: "Forbidden",
      url,
      headers: new Headers(),
      // The engine explains a refused event; a bare status would leave that to be invented
      json: () => Promise.resolve({ error: "You are not allowed to create workflows here" }),
    }) as unknown as Response)));
};

const renderDialog = (options: { homepages?: typeof homepages } = {}) => {
  const onClose = vi.fn();
  const onCreated = vi.fn();
  render(
    <ThemeProvider theme={appTheme} defaultMode="light">
      <NewWorkflowDialog homepages={options.homepages ?? homepages} onClose={onClose} onCreated={onCreated} />
    </ThemeProvider>
  );
  return { onClose, onCreated, dialog: screen.getByRole("dialog", { name: "New workflow" }) };
};

afterEach(() => vi.unstubAllGlobals());

describe("NewWorkflowDialog", () => {
  it("asks for the workflow's title and its first version's label", () => {
    const { dialog } = renderDialog();

    expect(within(dialog).getByRole("textbox", { name: /Title/ })).toHaveValue("");
    // A first version has to be called something, and 1.0 is what it usually is
    expect(within(dialog).getByRole("textbox", { name: /Version/ })).toHaveValue("1.0");
    // The diagram is not asked for here: it is authored in the editor, which opens on the new draft
    expect(within(dialog).queryByRole("textbox", { name: /BPMN/ })).not.toBeInTheDocument();
    // Nor is the choice of what runs: a new workflow's only version is a draft
    expect(within(dialog).queryByLabelText("Active")).not.toBeInTheDocument();
  });

  it("cannot be submitted without a title", async () => {
    const user = userEvent.setup();
    const { dialog } = renderDialog();

    expect(within(dialog).getByRole("button", { name: "Create" })).toBeDisabled();

    await user.type(within(dialog).getByRole("textbox", { name: /Title/ }), "Standard review");

    expect(within(dialog).getByRole("button", { name: "Create" })).toBeEnabled();
  });

  it("cannot be submitted without a version label either", async () => {
    const user = userEvent.setup();
    const { dialog } = renderDialog();
    await user.type(within(dialog).getByRole("textbox", { name: /Title/ }), "Standard review");

    await user.clear(within(dialog).getByRole("textbox", { name: /Version/ }));

    expect(within(dialog).getByRole("button", { name: "Create" })).toBeDisabled();
  });

  it("creates the workflow where it was told to, and reports the new draft", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch();
    const { dialog, onCreated, onClose } = renderDialog();

    await user.type(within(dialog).getByRole("textbox", { name: /Title/ }), "Standard review");
    await user.type(within(dialog).getByRole("textbox", { name: /Description/ }), "The first cut");
    await user.click(within(dialog).getByRole("button", { name: "Create" }));

    await waitFor(() => expect(onCreated).toHaveBeenCalledWith("/Workflows/created/created"));
    expect(onClose).toHaveBeenCalled();
    expect(fetchMock.mock.calls[0][0]).toBe("/Workflows");
  });

  it("offers the homepages a workflow may be stored in, when there is a choice", async () => {
    const user = userEvent.setup();
    const fetchMock = stubFetch();
    const { dialog } = renderDialog();

    await user.type(within(dialog).getByRole("textbox", { name: /Title/ }), "Platform behaviour");
    await user.click(within(dialog).getByRole("combobox", { name: "Stored in" }));
    await user.click(await screen.findByRole("option", { name: "System workflows" }));
    await user.click(within(dialog).getByRole("button", { name: "Create" }));

    await waitFor(() => expect(fetchMock.mock.calls[0][0]).toBe("/SystemWorkflows"));
  });

  it("does not ask where to store a workflow when there is only one place", () => {
    const { dialog } = renderDialog({ homepages: [ { path: "/Workflows", title: "Workflows" } ] });

    expect(within(dialog).queryByRole("combobox", { name: "Stored in" })).not.toBeInTheDocument();
  });

  it("cannot create a workflow when there is nowhere to put one", async () => {
    // A caller whose discovery found no readable homepage: the dialog says nothing can be created
    // rather than posting to nowhere
    const user = userEvent.setup();
    const { dialog } = renderDialog({ homepages: [] });

    await user.type(within(dialog).getByRole("textbox", { name: /Title/ }), "Standard review");

    expect(within(dialog).getByRole("button", { name: "Create" })).toBeDisabled();
  });

  it("keeps the dialog open and says why when the creation is refused", async () => {
    const user = userEvent.setup();
    stubFailingFetch();
    const { dialog, onClose } = renderDialog();

    await user.type(within(dialog).getByRole("textbox", { name: /Title/ }), "Standard review");
    await user.click(within(dialog).getByRole("button", { name: "Create" }));

    expect(await within(dialog).findByRole("alert"))
      .toHaveTextContent("You are not allowed to create workflows here");
    expect(onClose).not.toHaveBeenCalled();
  });
});
