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
import { ReLoginContext } from "@iap/frontend-commons/reLogin";

const PATH = "/content/victim";

const jsonResponse = (status: number, body: unknown) => new Response(JSON.stringify(body), {
  status,
  headers: { "Content-Type": "application/json" }
});

const archived = { "status.code": 200, status: "archived", items: [ PATH ], removedLinks: [] };

const dryRun = (extra: Record<string, unknown> = {}) => jsonResponse(200, {
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

    // The preview is a dry run, so nothing has been deleted merely by opening the dialog
    const url = new URL(fetchMock.mock.calls[0][0] as string);
    expect(url.searchParams.get("dryRun")).toBe("true");
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

  it("deletes on confirmation and reports the outcome", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(dryRun())
      .mockResolvedValueOnce(jsonResponse(200, archived));
    const onDeleted = vi.fn();
    renderButton({ onDeleted });

    await openDialog(user);
    await user.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => expect(onDeleted).toHaveBeenCalledWith(expect.objectContaining({ status: "archived" })));
    const url = new URL(fetchMock.mock.calls[1][0] as string);
    expect(url.searchParams.has("dryRun")).toBe(false);
    expect(url.searchParams.has("recursive")).toBe(false);
  });

  it("warns about referring resources and offers to take them too", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(dryRun({ executable: false, ...referenced, status: "dryRun" }))
      .mockResolvedValueOnce(jsonResponse(200, archived));
    renderButton();

    await user.click(screen.getByRole("button", { name: "Delete submission" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("referenced by 2 submissions");

    await user.click(screen.getByRole("button", { name: "Delete all of them" }));

    // Taking the referring resources along is exactly what the recursive option is for
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(new URL(fetchMock.mock.calls[1][0] as string).searchParams.get("recursive")).toBe("true");
  });

  it("states a veto and offers no way to override it", async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(200, { ...vetoed, status: "dryRun" }));
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
      .mockResolvedValueOnce(jsonResponse(409, referenced));
    const onDeleted = vi.fn();
    renderButton({ onDeleted });

    await openDialog(user);
    await user.click(screen.getByRole("button", { name: "Delete" }));

    // Something started referring to it between the preview and the deletion: the user is asked
    // again, with the new answer, instead of being told it simply failed
    expect(await screen.findByRole("alert")).toHaveTextContent("referenced by 2 submissions");
    expect(onDeleted).not.toHaveBeenCalled();
  });

  it("treats an already deleted resource as deleted", async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(dryRun())
      .mockResolvedValueOnce(jsonResponse(404, { "status.code": 404, status: "missing" }));
    const onDeleted = vi.fn();
    renderButton({ onDeleted });

    await openDialog(user);
    await user.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => expect(onDeleted).toHaveBeenCalled());
  });

  it("reports a refusal it cannot act on", async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(dryRun())
      .mockResolvedValueOnce(jsonResponse(403, {
        "status.code": 403,
        status: "denied",
        "status.message": "You are not allowed to delete everything this deletion would impact"
      }));
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
      .mockResolvedValueOnce(jsonResponse(200, archived));
    const onDeleted = vi.fn();
    renderButton({ onDeleted });

    // The server refuses anything unsafe on its own, so a missing preview downgrades the dialog
    // rather than blocking the user
    await openDialog(user);
    await user.click(screen.getByRole("button", { name: "Delete" }));

    await waitFor(() => expect(onDeleted).toHaveBeenCalled());
  });

  // The reason these requests go through useAuthenticatedFetch: a deletion is the request you least
  // want to lose to a session that expired while the confirmation dialog was open. The recovery
  // itself is covered in reLogin.test.tsx; what matters here is that the deletion takes part in it.
  it("recovers a deletion from a session that expired while it was being confirmed", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(dryRun())
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(jsonResponse(200, archived));
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

    await waitFor(() => expect(onDeleted).toHaveBeenCalledWith(expect.objectContaining({ status: "archived" })));
    expect(signIn).toHaveBeenCalled();
    // The DELETE was re-sent, not merely retried by the user: three calls for two intended requests
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  // The same expiry, but the user dismisses the sign-in. Reporting a transport failure here would be
  // false -- the server was reached and answered 401 -- and would send them to check their network.
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

    expect(await screen.findByText(/no longer signed in/)).toBeInTheDocument();
    expect(screen.queryByText(/could not be reached/)).not.toBeInTheDocument();
  });

  // The dry run fails the same way, and the fallback for a lost preview is a plain confirmation --
  // which would offer a Delete button that cannot possibly succeed.
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
      .mockRejectedValueOnce(new Error("offline"));
    renderButton();

    await openDialog(user);
    await user.click(screen.getByRole("button", { name: "Delete" }));

    expect(await screen.findByText(/could not be reached/)).toBeInTheDocument();
  });

  it("says a permanent deletion cannot be undone, and counts what goes with it", async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, "fetch").mockResolvedValue(dryRun({
      items: [ PATH, "/content/other" ],
      removedLinks: [ "/content/holder/iap:links/link0" ]
    }));
    renderButton({ permanent: true });

    await openDialog(user);

    expect(screen.getByText("This cannot be undone.")).toBeInTheDocument();
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

  it("warns about referring resources the user cannot see", async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, "fetch").mockResolvedValue(dryRun({
      executable: false,
      "status.message": "This item is referenced by 2 items you cannot see.",
      referrers: [],
      inaccessibleReferrers: 2
    }));
    renderButton();

    await user.click(screen.getByRole("button", { name: "Delete submission" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("you cannot see");
    expect(screen.getByRole("button", { name: "Delete all of them" })).toBeInTheDocument();
  });

  it("pluralizes the links it reports", async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, "fetch").mockResolvedValue(dryRun({
      removedLinks: [ "/content/a/iap:links/link0", "/content/b/iap:links/link0" ]
    }));
    renderButton();

    await openDialog(user);

    expect(screen.getByText("2 links to it will be removed.")).toBeInTheDocument();
  });

  it("explains a wordless failure, and lets the explanation be dismissed", async () => {
    const user = userEvent.setup();
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(dryRun())
      .mockResolvedValueOnce(jsonResponse(400, { "status.code": 400, status: "invalid" }));
    renderButton();

    await openDialog(user);
    await user.click(screen.getByRole("button", { name: "Delete" }));

    expect(await screen.findByText("The submission could not be deleted.")).toBeInTheDocument();
    // The shared ErrorDialog's close button is an unlabelled icon, so it is reached through the dialog
    await user.click(within(screen.getByRole("dialog", { name: "Error" })).getByRole("button"));
    await waitFor(() =>
      expect(screen.queryByText("The submission could not be deleted.")).not.toBeInTheDocument());
  });
});
