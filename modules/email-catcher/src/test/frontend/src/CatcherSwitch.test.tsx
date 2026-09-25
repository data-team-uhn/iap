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

import CatcherSwitch from "@iap/email-catcher/CatcherSwitch";
import { appTheme } from "@iap/frontend-commons/appTheme";

const accepting = () =>
  vi.fn((_url: string, _init?: RequestInit) =>
    Promise.resolve(new Response("{}", { status: 200 })));

const showing = (enabled: boolean, onChanged = vi.fn()) => {
  render(
    <ThemeProvider theme={appTheme} defaultMode="light">
      <CatcherSwitch enabled={enabled} onChanged={onChanged} />
    </ThemeProvider>
  );
  return onChanged;
};

// MUI renders a Switch as role="switch", not "checkbox".
const toggle = () => screen.getByRole("switch", { name: "Catch mail instead of sending it" });

describe("CatcherSwitch", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("asks before it starts catching, and says what that costs", async () => {
    vi.stubGlobal("fetch", accepting());
    showing(false);

    await userEvent.click(toggle());

    expect(screen.getByText("Catch mail instead of sending it?")).toBeInTheDocument();
    expect(screen.getByText(/will not receive it/)).toBeInTheDocument();
  });

  it("asks before it stops, too, because the other direction reaches real people", async () => {
    // Switching off on a test instance starts delivering to whatever addresses the data holds.
    vi.stubGlobal("fetch", accepting());
    showing(true);

    await userEvent.click(toggle());

    expect(screen.getByText("Start sending mail again?")).toBeInTheDocument();
    expect(screen.getByText(/may belong to real people/)).toBeInTheDocument();
  });

  it("writes nothing until the confirmation is accepted", async () => {
    const fetchMock = accepting();
    vi.stubGlobal("fetch", fetchMock);
    showing(false);

    await userEvent.click(toggle());
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(fetchMock).not.toHaveBeenCalled();
    // waitFor, because MUI keeps the dialog mounted for its exit transition.
    await waitFor(() =>
      expect(screen.queryByText("Catch mail instead of sending it?")).not.toBeInTheDocument());
  });

  it("writes nothing when the dialog is dismissed rather than answered", async () => {
    // Escape and a backdrop click go through the Dialog's own onClose, not the Cancel button.
    const fetchMock = accepting();
    vi.stubGlobal("fetch", fetchMock);
    showing(false);

    await userEvent.click(toggle());
    await userEvent.keyboard("{Escape}");

    expect(fetchMock).not.toHaveBeenCalled();
    await waitFor(() =>
      expect(screen.queryByText("Catch mail instead of sending it?")).not.toBeInTheDocument());
  });

  it("posts the new setting once confirmed, and tells the caller to read it back", async () => {
    const fetchMock = accepting();
    vi.stubGlobal("fetch", fetchMock);
    const onChanged = showing(false);

    await userEvent.click(toggle());
    await userEvent.click(screen.getByRole("button", { name: "Catch mail" }));

    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(fetchMock.mock.calls[0][0]).toBe("/CaughtMail.catching.json");
    const init = fetchMock.mock.calls[0][1]!;
    expect(init.method).toBe("POST");
    expect((init.body as URLSearchParams).get("enabled")).toBe("true");
  });

  it("asks to be switched off when it is on", async () => {
    const fetchMock = accepting();
    vi.stubGlobal("fetch", fetchMock);
    showing(true);

    await userEvent.click(toggle());
    await userEvent.click(screen.getByRole("button", { name: "Send mail" }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const init = fetchMock.mock.calls[0][1]!;
    expect((init.body as URLSearchParams).get("enabled")).toBe("false");
  });

  it("announces the change, so the banner on this page notices", async () => {
    vi.stubGlobal("fetch", accepting());
    const heard = vi.fn();
    window.addEventListener("iap:mail-catching-changed", heard);
    showing(false);

    await userEvent.click(toggle());
    await userEvent.click(screen.getByRole("button", { name: "Catch mail" }));

    await waitFor(() => expect(heard).toHaveBeenCalled());
    window.removeEventListener("iap:mail-catching-changed", heard);
  });

  it("announces nothing when the write was refused", async () => {
    vi.stubGlobal("fetch", vi.fn((_url: string, _init?: RequestInit) =>
      Promise.resolve(new Response("{}", { status: 403 }))));
    const heard = vi.fn();
    window.addEventListener("iap:mail-catching-changed", heard);
    showing(false);

    await userEvent.click(toggle());
    await userEvent.click(screen.getByRole("button", { name: "Catch mail" }));

    await screen.findByRole("alert");
    expect(heard).not.toHaveBeenCalled();
    window.removeEventListener("iap:mail-catching-changed", heard);
  });

  it("says so when the setting could not be written, rather than closing as though it had", async () => {
    vi.stubGlobal("fetch", vi.fn((_url: string, _init?: RequestInit) =>
      Promise.resolve(new Response("{}", { status: 403 }))));
    const onChanged = showing(false);

    await userEvent.click(toggle());
    await userEvent.click(screen.getByRole("button", { name: "Catch mail" }));

    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(onChanged).not.toHaveBeenCalled();
  });
});
