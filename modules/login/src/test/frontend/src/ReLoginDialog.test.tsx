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
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { appTheme } from "@iap/frontend-commons/appTheme";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { ReLoginDialog, ReLoginProvider } from "@iap/login/ReLoginDialog";

import { withMessages } from "./messages.fixture";

// Runs a request through the provider, so the tests exercise the whole round trip: expired
// session, dialog, sign-in, re-sent request.
function Caller({ label, onResult }: { label: string; onResult: (outcome: string) => void }) {
  const authenticatedFetch = useAuthenticatedFetch();
  return (
    <button
      type="button"
      onClick={() => {
        authenticatedFetch("/data.json")
          .then(response => response.text())
          .then(text => onResult(`${label}:${text}`))
          .catch((err: unknown) => onResult(`${label}:rejected:${(err as Error).message}`));
      }}
    >
      {label}
    </button>
  );
}

const expired = () => ({ ok: false, status: 401, url: "/data.json" }) as unknown as Response;
const ok = (body: string) => ({ ok: true, status: 200, url: "/data.json", text: () => Promise.resolve(body) }) as unknown as Response;

// Answers the sign-in form's POST to Sling's authentication endpoint
const signInResponse = (success: boolean) => ({ ok: success, status: success ? 200 : 403, statusText: success ? "OK" : "Forbidden" }) as Response;

describe("ReLoginDialog", () => {
  it("says what happened and offers the sign-in form", () => {
    render(withMessages(
      <ThemeProvider theme={appTheme} defaultMode="light">
        <ReLoginDialog open onSignedIn={vi.fn()} onAbandoned={vi.fn()} />
      </ThemeProvider>
    ));

    expect(screen.getByRole("heading", { name: "Your session has expired" })).toBeInTheDocument();
    expect(screen.getByText("Sign in again to continue. Nothing you have entered will be lost.")).toBeInTheDocument();
    expect(screen.getByLabelText(/Username/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Password/)).toBeInTheDocument();
  });

  it("stays out of the way until it is needed", () => {
    render(withMessages(
      <ThemeProvider theme={appTheme} defaultMode="light">
        <ReLoginDialog open={false} onSignedIn={vi.fn()} onAbandoned={vi.fn()} />
      </ThemeProvider>
    ));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("is not dismissed by escape, since the work behind it is waiting on the session", async () => {
    const user = userEvent.setup();
    const onAbandoned = vi.fn();
    render(withMessages(
      <ThemeProvider theme={appTheme} defaultMode="light">
        <ReLoginDialog open onSignedIn={vi.fn()} onAbandoned={onAbandoned} />
      </ThemeProvider>
    ));

    await user.keyboard("{Escape}");

    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(onAbandoned).not.toHaveBeenCalled();
  });

  it("offers a way out, for a user who cannot produce the credentials it wants", async () => {
    const user = userEvent.setup();
    const onAbandoned = vi.fn();
    render(withMessages(
      <ThemeProvider theme={appTheme} defaultMode="light">
        <ReLoginDialog open onSignedIn={vi.fn()} onAbandoned={onAbandoned} />
      </ThemeProvider>
    ));

    await user.click(screen.getByRole("button", { name: "Cancel" }));

    expect(onAbandoned).toHaveBeenCalled();
  });
});

describe("ReLoginProvider", () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const renderWithProvider = (callers: string[], onResult: (outcome: string) => void) => render(withMessages(
    <ThemeProvider theme={appTheme} defaultMode="light">
      <ReLoginProvider>
        {callers.map(label => <Caller key={label} label={label} onResult={onResult} />)}
      </ReLoginProvider>
    </ThemeProvider>
  ));

  // Signs in, queueing the answers the re-sent requests should get afterwards. Order matters: the
  // form's own POST is the next request to go out, and the retries follow it.
  const signIn = async (
    user: ReturnType<typeof userEvent.setup>,
    { success = true, then = [] }: { success?: boolean; then?: Response[] } = {},
  ) => {
    await user.type(screen.getByLabelText(/Username/), "jdoe");
    await user.type(screen.getByLabelText(/Password/), "secret");
    fetchMock.mockResolvedValueOnce(signInResponse(success));
    then.forEach(response => fetchMock.mockResolvedValueOnce(response));
    await user.click(screen.getByRole("button", { name: "Sign in" }));
  };

  it("shows nothing until a request runs into an expired session", () => {
    renderWithProvider(["A"], vi.fn());

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("prompts for a sign-in, then re-sends the request that was interrupted", async () => {
    const user = userEvent.setup();
    const onResult = vi.fn();
    fetchMock.mockResolvedValueOnce(expired());
    renderWithProvider(["A"], onResult);

    await user.click(screen.getByRole("button", { name: "A" }));

    expect(await screen.findByRole("heading", { name: "Your session has expired" })).toBeInTheDocument();
    expect(onResult).not.toHaveBeenCalled();

    await signIn(user, { then: [ok("recovered")] });

    await waitFor(() => { expect(onResult).toHaveBeenCalledWith("A:recovered"); });
    await waitFor(() => { expect(screen.queryByRole("dialog")).not.toBeInTheDocument(); });
  });

  it("re-sends every request that was waiting, not just the first", async () => {
    const user = userEvent.setup();
    const onResult = vi.fn();
    fetchMock.mockResolvedValueOnce(expired()).mockResolvedValueOnce(expired());
    renderWithProvider(["A", "B"], onResult);

    // Both have to fail before the dialog opens; once it has, the modal puts the rest of the page
    // behind aria-hidden, so these go through fireEvent rather than user-event's pointer checks.
    fireEvent.click(screen.getByRole("button", { name: "A" }));
    fireEvent.click(screen.getByRole("button", { name: "B" }));
    await screen.findByRole("heading", { name: "Your session has expired" });

    await signIn(user, { then: [ok("first"), ok("second")] });

    await waitFor(() => { expect(onResult).toHaveBeenCalledTimes(2); });
    expect(onResult.mock.calls.map(([outcome]) => (outcome as string).split(":")[0]).sort()).toEqual(["A", "B"]);
  });

  it("stays open when the credentials are refused", async () => {
    const user = userEvent.setup();
    const onResult = vi.fn();
    fetchMock.mockResolvedValueOnce(expired());
    renderWithProvider(["A"], onResult);
    await user.click(screen.getByRole("button", { name: "A" }));
    await screen.findByRole("heading", { name: "Your session has expired" });

    await signIn(user, { success: false });

    expect(await screen.findByText("Invalid username or password")).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(onResult).not.toHaveBeenCalled();
  });

  it("fails the waiting requests when the user gives up, instead of leaving them pending", async () => {
    const user = userEvent.setup();
    const onResult = vi.fn();
    fetchMock.mockResolvedValueOnce(expired());
    renderWithProvider(["A"], onResult);
    await user.click(screen.getByRole("button", { name: "A" }));
    await screen.findByRole("heading", { name: "Your session has expired" });

    await user.click(screen.getByRole("button", { name: "Cancel" }));

    await waitFor(() => {
      expect(onResult).toHaveBeenCalledWith("A:rejected:Not authenticated, and signing in was abandoned: /data.json");
    });
    await waitFor(() => { expect(screen.queryByRole("dialog")).not.toBeInTheDocument(); });
  });

  it("prompts again the next time the session expires", async () => {
    const user = userEvent.setup();
    const onResult = vi.fn();
    fetchMock.mockResolvedValueOnce(expired());
    renderWithProvider(["A"], onResult);
    await user.click(screen.getByRole("button", { name: "A" }));
    await screen.findByRole("heading", { name: "Your session has expired" });
    await signIn(user, { then: [ok("recovered")] });
    await waitFor(() => { expect(screen.queryByRole("dialog")).not.toBeInTheDocument(); });

    fetchMock.mockResolvedValueOnce(expired());
    await user.click(screen.getByRole("button", { name: "A" }));

    // The queue was emptied by the first sign-in, so this is a fresh prompt rather than a stale one
    expect(await screen.findByRole("heading", { name: "Your session has expired" })).toBeInTheDocument();
    await signIn(user, { then: [ok("recovered again")] });
    await waitFor(() => { expect(onResult).toHaveBeenLastCalledWith("A:recovered again"); });
  });

  // The dialog is asked for again before the closing one has finished its exit transition, so the
  // second prompt is the same form the first sign-in was submitted on: it has to be usable, or the
  // only way out of the loop is Cancel, which is exactly the lost work this dialog exists to save.
  it("offers a form that can be submitted again when the re-sent request expires too", async () => {
    const user = userEvent.setup();
    const onResult = vi.fn();
    fetchMock.mockResolvedValueOnce(expired());
    renderWithProvider(["A"], onResult);
    await user.click(screen.getByRole("button", { name: "A" }));
    await screen.findByRole("heading", { name: "Your session has expired" });

    await signIn(user, { then: [expired()] });

    await screen.findByRole("heading", { name: "Your session has expired" });
    expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled();

    // And signing in again really does get the request through
    await signIn(user, { then: [ok("recovered at last")] });
    await waitFor(() => { expect(onResult).toHaveBeenCalledWith("A:recovered at last"); });
  });

  it("fails the waiting requests when it goes away, rather than taking them with it", async () => {
    const onResult = vi.fn();
    fetchMock.mockResolvedValueOnce(expired());
    const { unmount } = renderWithProvider(["A"], onResult);
    fireEvent.click(screen.getByRole("button", { name: "A" }));
    await screen.findByRole("heading", { name: "Your session has expired" });

    unmount();

    await waitFor(() => {
      expect(onResult).toHaveBeenCalledWith("A:rejected:Not authenticated, and signing in was abandoned: /data.json");
    });
  });
});
