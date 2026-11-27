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

import type { ComponentProps } from "react";

import { ThemeProvider } from "@mui/material/styles";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import DeleteItem from "@iap/deletion/DeleteItem";
import { appTheme } from "@iap/frontend-commons/appTheme";
import { jsonResponse } from "@iap/frontend-commons/fetch.fixture";
import { ReLoginContext } from "@iap/frontend-commons/reLogin";

const PATH = "/content/victim";

// The engine's answer to an event it carried out
const completed = () => jsonResponse({ status: "completed" });

const dryRun = (extra: Record<string, unknown> = {}) => jsonResponse({
  "status.code": 200,
  status: "dryRun",
  executable: true,
  items: [ PATH ],
  removedLinks: [],
  ...extra
});

const referenced = {
  "status.code": 409,
  status: "referenced",
  "status.message": "This item is referenced by 2 submissions (S-1, S-2).",
  items: [ PATH ],
  removedLinks: [],
  referrers: [ { type: "sub:Submission", label: "submission", count: 2, names: [ "S-1", "S-2" ] } ],
  inaccessibleReferrers: 0
};

const vetoed = {
  "status.code": 409,
  status: "vetoed",
  "status.message": "This resource is protected from deletion",
  vetoes: [ { vetoer: "undeletable", path: PATH, reason: "This resource is protected from deletion" } ]
};

const renderButton = (props: Partial<ComponentProps<typeof DeleteItem>> = {}) => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <DeleteItem path={PATH} type="submission" {...props} />
  </ThemeProvider>
);

/** Open the dialog and wait for the dry run to have been rendered. */
const openDialog = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(screen.getByRole("button", { name: "Delete submission" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "Delete" })).toBeInTheDocument());
};

describe("DeleteItem", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("asks what the deletion would do before asking the user to confirm", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(dryRun());
    renderButton({ name: "My submission" });

    await openDialog(user);

    // The preview only reads, so nothing has been deleted merely by opening the dialog
    expect(fetchMock.mock.calls[0][0]).toBe(`${PATH}.deletion.json`);
    expect(fetchMock.mock.calls[0][1]?.method).toBeUndefined();
    expect(screen.getByText(/You are about to delete submission "My submission"/)).toBeInTheDocument();
    expect(screen.getByText(/moved to the archive/)).toBeInTheDocument();
  });

  it("names the resource after the last path segment when no name is given", async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, "fetch").mockResolvedValue(dryRun());
    renderButton();

    await openDialog(user);

    expect(screen.getByText(/submission "victim"/)).toBeInTheDocument();
  });

  it("sends the delete event on confirmation, then says it is done", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(dryRun())
      .mockResolvedValueOnce(completed());
    const onDeleted = vi.fn();
    renderButton({ onDeleted });

    await openDialog(user);
    await user.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => expect(onDeleted).toHaveBeenCalled());
    expect(fetchMock.mock.calls[1][0]).toBe(`${PATH}.delete.json`);
    expect(fetchMock.mock.calls[1][1]?.method).toBe("POST");
  });

  it("states what refers to the resource and offers no deletion", async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, "fetch").mockResolvedValue(dryRun({ ...referenced, executable: false, status: "dryRun" }));
    renderButton();

    await user.click(screen.getByRole("button", { name: "Delete submission" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("referenced by 2 submissions");
    expect(screen.getByRole("heading", { name: "Cannot delete this submission" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Delete/ })).not.toBeInTheDocument();
  });

  it("states a veto and offers no way to override it", async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ ...vetoed, status: "dryRun" }));
    renderButton();

    await user.click(screen.getByRole("button", { name: "Delete submission" }));

    expect(await screen.findByText("This resource is protected from deletion")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Cannot delete this submission" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Delete/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Close" })).toBeInTheDocument();
  });

  it("keeps the dialog open when the answer changed since the preview", async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(dryRun())
      .mockResolvedValueOnce(jsonResponse({ error: "This item is referenced by 2 submissions (S-1, S-2)." }, 409))
      .mockResolvedValueOnce(dryRun({ ...referenced, executable: false, status: "dryRun" }));
    const onDeleted = vi.fn();
    renderButton({ onDeleted });

    await openDialog(user);
    await user.click(screen.getByRole("button", { name: "Delete" }));

    // Something started referring to it between the preview and the deletion: the user is asked
    // again, with the new answer, instead of being told it simply failed
    expect(await screen.findByRole("alert")).toHaveTextContent("referenced by 2 submissions");
    expect(onDeleted).not.toHaveBeenCalled();
  });

  it("reports a refusal it cannot act on", async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(dryRun())
      .mockResolvedValueOnce(jsonResponse({ error: "You are not allowed to delete this submission" }, 403));
    const onDeleted = vi.fn();
    renderButton({ onDeleted });

    await openDialog(user);
    await user.click(screen.getByRole("button", { name: "Delete" }));

    expect(await screen.findByText(/not allowed to delete/)).toBeInTheDocument();
    expect(onDeleted).not.toHaveBeenCalled();
  });

  it("still allows a deletion whose preview could not be fetched", async () => {
    const user = userEvent.setup();
    vi.spyOn(console, "error").mockImplementation(() => {});
    vi.spyOn(globalThis, "fetch")
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValueOnce(completed());
    const onDeleted = vi.fn();
    renderButton({ onDeleted });

    await openDialog(user);
    await user.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => expect(onDeleted).toHaveBeenCalled());
  });

  // The session expires while the dialog is open, the user signs back in, and the deletion goes
  // through. reLogin.test.tsx covers the sign-in itself.
  it("recovers a deletion from a session that expired while it was being confirmed", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(dryRun())
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(completed());
    const signIn = vi.fn().mockResolvedValue(true);
    const onDeleted = vi.fn();
    render(
      <ThemeProvider theme={appTheme} defaultMode="light">
        <ReLoginContext value={signIn}>
          <DeleteItem path={PATH} type="submission" onDeleted={onDeleted} />
        </ReLoginContext>
      </ThemeProvider>
    );

    await openDialog(user);
    await user.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => expect(onDeleted).toHaveBeenCalled());
    expect(signIn).toHaveBeenCalled();
    // The event was re-sent, not merely retried by the user: three calls for two intended requests
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  // The same expiry, but the user dismisses the sign-in
  it("asks the user to sign in when a deletion is abandoned at the sign-in prompt", async () => {
    const user = userEvent.setup();
    vi.spyOn(console, "error").mockImplementation(() => {});
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(dryRun())
      .mockResolvedValueOnce(new Response(null, { status: 401 }));
    const signIn = vi.fn().mockResolvedValue(false);
    render(
      <ThemeProvider theme={appTheme} defaultMode="light">
        <ReLoginContext value={signIn}>
          <DeleteItem path={PATH} type="submission" />
        </ReLoginContext>
      </ThemeProvider>
    );

    await openDialog(user);
    await user.click(screen.getByRole("button", { name: "Delete" }));

    expect(await screen.findByText(/could not be deleted\. Your session has expired/)).toBeInTheDocument();
    expect(screen.queryByText(/could not be reached/)).not.toBeInTheDocument();
  });

  // The dry run fails the same way. A plain confirmation would offer a Delete button that cannot
  // succeed.
  it("does not offer a confirmation when the session is already gone", async () => {
    const user = userEvent.setup();
    vi.spyOn(console, "error").mockImplementation(() => {});
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 401 }));
    const signIn = vi.fn().mockResolvedValue(false);
    render(
      <ThemeProvider theme={appTheme} defaultMode="light">
        <ReLoginContext value={signIn}>
          <DeleteItem path={PATH} type="submission" />
        </ReLoginContext>
      </ThemeProvider>
    );

    await user.click(screen.getByRole("button", { name: "Delete submission" }));

    expect(await screen.findByText(/no longer signed in/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete" })).not.toBeInTheDocument();
  });

  it("reports a deletion that could not be sent", async () => {
    const user = userEvent.setup();
    vi.spyOn(console, "error").mockImplementation(() => {});
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(dryRun())
      // What fetch rejects with when the request never completes
      .mockRejectedValueOnce(new TypeError("Failed to fetch"));
    renderButton();

    await openDialog(user);
    await user.click(screen.getByRole("button", { name: "Delete" }));

    expect(await screen.findByText(/could not be reached/)).toBeInTheDocument();
  });

  it("says the deletion can be undone, and counts what goes with it", async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, "fetch").mockResolvedValue(dryRun({
      items: [ PATH, "/content/other" ],
      removedLinks: [ "/content/holder/link:links/link0" ]
    }));
    renderButton();

    await openDialog(user);

    expect(screen.getByText(/moved to the archive, and can be restored/)).toBeInTheDocument();
    expect(screen.getByText(/removes 2 items in total/)).toBeInTheDocument();
    expect(screen.getByText("1 link to it will be removed.")).toBeInTheDocument();
  });

  it("closes without deleting on cancel", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(dryRun());
    const onClose = vi.fn();
    renderButton({ onClose });

    await openDialog(user);
    await user.click(screen.getByRole("button", { name: "Cancel" }));

    expect(onClose).toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("renders as a labelled button when asked to", async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, "fetch").mockResolvedValue(dryRun());
    renderButton({ variant: "extended", label: "Remove this", size: "small" });

    await user.click(screen.getByRole("button", { name: "Remove this" }));

    expect(await screen.findByRole("button", { name: "Delete" })).toBeInTheDocument();
  });

  it("does nothing while disabled", () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(dryRun());
    renderButton({ disabled: true });

    expect(screen.getByRole("button", { name: "Delete submission" })).toBeDisabled();
    expect(fetchMock).not.toHaveBeenCalled();
  });
  it("falls back to calling the resource an item when given no type", async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, "fetch").mockResolvedValue(dryRun());
    render(
      <ThemeProvider theme={appTheme} defaultMode="light">
        <DeleteItem path={PATH} />
      </ThemeProvider>
    );

    await user.click(screen.getByRole("button", { name: "Delete item" }));

    expect(await screen.findByRole("heading", { name: "Delete this item?" })).toBeInTheDocument();
    expect(screen.getByText(/You are about to delete "victim"/)).toBeInTheDocument();
  });

  it("renders as a plain text button, and as a small icon", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(dryRun());
    const { unmount } = renderButton({ variant: "text" });
    expect(screen.getByRole("button", { name: "Delete submission" })).toBeInTheDocument();
    unmount();

    renderButton({ size: "small" });
    expect(screen.getByRole("button", { name: "Delete submission" })).toBeInTheDocument();
  });

  // Those referrers are hidden by access control or archived, so they are only counted
  it("offers no deletion when some referring resources are out of the user's reach", async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, "fetch").mockResolvedValue(dryRun({
      ...referenced,
      status: "dryRun",
      executable: false,
      "status.message": "This item is referenced by 2 submissions (S-1, S-2) and 1 other item you cannot see.",
      inaccessibleReferrers: 1
    }));
    renderButton();

    await user.click(screen.getByRole("button", { name: "Delete submission" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("1 other item you cannot see");
    expect(screen.getByRole("heading", { name: "Cannot delete this submission" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Delete/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Close" })).toBeInTheDocument();
  });

  it("pluralizes the links it reports", async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, "fetch").mockResolvedValue(dryRun({
      removedLinks: [ "/content/a/link:links/link0", "/content/b/link:links/link0" ]
    }));
    renderButton();

    await openDialog(user);

    expect(screen.getByText("2 links to it will be removed.")).toBeInTheDocument();
  });

  it("explains a wordless failure, and lets the explanation be dismissed", async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(dryRun())
      .mockResolvedValueOnce(new Response(null, { status: 400 }));
    renderButton();

    await openDialog(user);
    await user.click(screen.getByRole("button", { name: "Delete" }));

    expect(await screen.findByText(/^The submission could not be deleted\./)).toBeInTheDocument();
    // The shared ErrorDialog's close button is an unlabelled icon, so it is reached through the dialog
    await user.click(within(screen.getByRole("dialog", { name: "Error" })).getByRole("button"));
    await waitFor(() =>
      expect(screen.queryByText(/^The submission could not be deleted\./)).not.toBeInTheDocument());
  });
});
