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

import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import Notifications from "@iap/homepage/Notifications";

const navigate = vi.fn();

vi.mock("react-router", async importOriginal => ({
  ...await importOriginal<Record<string, unknown>>(),
  useNavigate: () => navigate,
}));

const doFetch = vi.fn();

vi.mock("@iap/frontend-commons/reLogin", async importOriginal => ({
  ...await importOriginal<Record<string, unknown>>(),
  useAuthenticatedFetch: () => doFetch,
}));

// One row as the pagination servlet serializes it
function row(name: string, read: boolean | string, extras: Record<string, unknown> = {}) {
  return {
    "@path": `/Notifications/aa/bb/cc/${name}`,
    "@name": name,
    line: `Something happened to ${name}`,
    read,
    subject: `/Submissions/${name}`,
    "jcr:created": "2026-08-30T12:00:00.000Z",
    ...extras,
  };
}

function respond(body: unknown, ok = true) {
  return { ok, status: ok ? 200 : 500, json: () => Promise.resolve(body) };
}

function servesNotifications(rows: Record<string, unknown>[]) {
  doFetch.mockImplementation((url: string) => {
    if (url.startsWith("/Notifications.paginate.json")) {
      return Promise.resolve(respond({ rows, offset: 0, limit: 100,
        returnedrows: rows.length, totalrows: rows.length, totalIsApproximate: false }));
    }
    if (url.endsWith(".markRead.json")) {
      return Promise.resolve(respond({ status: "ok" }));
    }
    return Promise.reject(new Error(`Unexpected request: ${url}`));
  });
}

describe("Notifications", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("shows how many notifications are unread", async () => {
    servesNotifications([row("one", false), row("two", "true"), row("three", false)]);

    render(<Notifications />);

    // The read one does not count, whether the boolean round-tripped as a boolean or as a string
    expect(await screen.findByText("2")).toBeInTheDocument();
  });

  it("lists the notifications, newest first as served, and marks the unread ones read", async () => {
    servesNotifications([row("one", false), row("two", true)]);
    render(<Notifications />);

    fireEvent.click(screen.getByRole("button", { name: "Notifications" }));

    expect(await screen.findByText("Something happened to one")).toBeInTheDocument();
    expect(screen.getByText("Something happened to two")).toBeInTheDocument();
    // Shown is read: only the unread one is posted about
    await waitFor(() => {
      expect(doFetch).toHaveBeenCalledWith(
        "/Notifications/aa/bb/cc/one.markRead.json", { method: "POST" });
    });
    expect(doFetch).not.toHaveBeenCalledWith(
      "/Notifications/aa/bb/cc/two.markRead.json", { method: "POST" });
  });

  it("says so when there are no notifications", async () => {
    servesNotifications([]);
    render(<Notifications />);

    fireEvent.click(screen.getByRole("button", { name: "Notifications" }));

    expect(await screen.findByText("You have no notifications")).toBeInTheDocument();
  });

  it("follows a notification to what it is about", async () => {
    servesNotifications([row("one", true)]);
    render(<Notifications />);
    fireEvent.click(screen.getByRole("button", { name: "Notifications" }));

    fireEvent.click(await screen.findByText("Something happened to one"));

    expect(navigate).toHaveBeenCalledWith("/Submissions/one");
    await waitFor(() => {
      expect(screen.queryByText("Something happened to one")).not.toBeInTheDocument();
    });
  });

  it("cannot follow a notification about nothing", async () => {
    servesNotifications([row("one", true, { subject: undefined, "jcr:created": undefined })]);
    render(<Notifications />);

    fireEvent.click(screen.getByRole("button", { name: "Notifications" }));

    const entry = await screen.findByText("Something happened to one");
    expect(entry.closest("li")).toHaveAttribute("aria-disabled", "true");
  });

  it("admits when the list cannot be loaded", async () => {
    doFetch.mockRejectedValue(new Error("the session expired"));
    render(<Notifications />);

    fireEvent.click(screen.getByRole("button", { name: "Notifications" }));

    expect(await screen.findByText("The notifications could not be loaded")).toBeInTheDocument();
  });

  it("admits when the read markers cannot be recorded", async () => {
    doFetch.mockImplementation((url: string) => {
      if (url.startsWith("/Notifications.paginate.json")) {
        return Promise.resolve(respond({ rows: [row("one", false)], offset: 0, limit: 100,
          returnedrows: 1, totalrows: 1, totalIsApproximate: false }));
      }
      return Promise.resolve(respond({ status: "error" }, false));
    });
    render(<Notifications />);

    fireEvent.click(screen.getByRole("button", { name: "Notifications" }));

    expect(await screen.findByText("The notifications could not be loaded")).toBeInTheDocument();
  });

  it("keeps the badge fresh while the page stays open", async () => {
    servesNotifications([]);
    vi.useFakeTimers();
    try {
      render(<Notifications />);
      await vi.waitFor(() => expect(doFetch).toHaveBeenCalledTimes(1));

      servesNotifications([row("one", false)]);
      await vi.advanceTimersByTimeAsync(60_000);

      expect(doFetch).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it("closes the dropdown again", async () => {
    servesNotifications([]);
    render(<Notifications />);
    fireEvent.click(screen.getByRole("button", { name: "Notifications" }));
    const entry = await screen.findByText("You have no notifications");

    fireEvent.keyDown(entry, { key: "Escape", code: "Escape" });

    await waitFor(() => {
      expect(screen.queryByText("You have no notifications")).not.toBeInTheDocument();
    });
  });
});
