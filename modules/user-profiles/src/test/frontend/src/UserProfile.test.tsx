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

import type { Profile, ProfileField } from "@iap/user-profiles/profileApi";
import UserProfile from "@iap/user-profiles/UserProfile";

const field = (overrides: Partial<ProfileField> = {}): ProfileField => ({
  name: "email",
  label: "Email address",
  kind: "profile",
  dataType: "text",
  required: false,
  multiple: false,
  usable: true,
  readable: true,
  editable: true,
  provenance: "local",
  values: [],
  ...overrides,
});

const FULL_NAME = field({ name: "fullName", label: "Full name", values: [ "Jane Doe" ] });
const LOCALE = field({
  name: "locale",
  label: "Language",
  kind: "preference",
  allowedValues: [ "en", "fr" ],
  values: [ "en" ],
});

const profile = (overrides: Partial<Profile> = {}): Profile => ({
  account: "jdoe",
  external: false,
  idp: "",
  principals: [],
  fields: [ FULL_NAME, LOCALE ],
  ...overrides,
});

// A fetch answering the read with the given profile and every write with the given outcome. Reads
// are answered from a queue when one is supplied, so that a test can assert the page re-reads.
const stub = (
  reads: Profile[],
  outcome: unknown = { status: "success", forbidden: false, changed: [], refused: {} },
) => {
  const fetcher = vi.fn((_url: string, init?: RequestInit) => Promise.resolve({
    ok: true,
    status: 200,
    // Read by the authenticated fetch to tell an answer apart from a redirect to the sign-in page
    url: "http://localhost/system/iap/profile.json",
    json: () => Promise.resolve(init?.method === "POST" ? outcome : (reads.length > 1 ? reads.shift() : reads[0])),
  } as unknown as Response));
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
};

afterEach(() => vi.unstubAllGlobals());

describe("UserProfile", () => {
  it("identifies the person and groups the catalogue into what it is about", async () => {
    stub([ profile() ]);

    render(<UserProfile />);

    expect(await screen.findByRole("heading", { name: "Jane Doe" })).toBeInTheDocument();
    expect(screen.getByText("jdoe")).toBeInTheDocument();
    // The two groups are the catalogue's own distinction, not a hardcoded list of fields
    expect(screen.getByRole("heading", { name: "About you" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Settings" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Full name" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Language" })).toBeInTheDocument();
  });

  it("says which identity provider a synchronized account signs in through", async () => {
    stub([ profile({ external: true, idp: "keycloak" }) ]);

    render(<UserProfile />);

    expect(await screen.findByText("Signed in via keycloak")).toBeInTheDocument();
  });

  it("falls back to the account name when no full name is recorded", async () => {
    stub([ profile({ fields: [ field({ name: "fullName", label: "Full name" }) ] }) ]);

    render(<UserProfile />);

    expect(await screen.findByRole("heading", { name: "jdoe" })).toBeInTheDocument();
  });

  it("leaves out a group the catalogue has nothing in", async () => {
    stub([ profile({ fields: [ LOCALE ] }) ]);

    render(<UserProfile />);

    expect(await screen.findByRole("heading", { name: "Settings" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "About you" })).not.toBeInTheDocument();
  });

  it("saves only what was changed, then re-reads what was stored", async () => {
    const saved = profile({ fields: [ FULL_NAME, { ...LOCALE, values: [ "fr" ] } ] });
    const fetcher = stub([ profile(), saved ]);

    render(<UserProfile />);
    fireEvent.change(await screen.findByRole("textbox", { name: "Full name" }), {
      target: { value: "Jane R. Doe" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    expect(await screen.findByText("Your profile has been saved.")).toBeInTheDocument();
    const posted = fetcher.mock.calls.find(call => call[1]?.method === "POST");
    // Only the edited field: sending an untouched one back would refuse the whole save over
    // something the person never touched
    expect(posted?.[1]?.body).toBe("fullName=Jane+R.+Doe");
    await waitFor(() => { expect(fetcher.mock.calls.filter(call => !call[1]).length).toBe(2); });
  });

  it("keeps the save button out of reach until something is actually different", async () => {
    stub([ profile() ]);

    render(<UserProfile />);
    const save = await screen.findByRole("button", { name: "Save changes" });
    expect(save).toBeDisabled();

    fireEvent.change(screen.getByRole("textbox", { name: "Full name" }), { target: { value: "Jane Roe" } });
    expect(save).toBeEnabled();

    // Typing it back the way it was is not a change either
    fireEvent.change(screen.getByRole("textbox", { name: "Full name" }), { target: { value: "Jane Doe" } });
    expect(save).toBeDisabled();
  });

  it("puts each refusal against its own control and saves nothing", async () => {
    stub([ profile() ], {
      status: "error",
      forbidden: false,
      changed: [],
      refused: { fullName: "That is not a name" },
    });

    render(<UserProfile />);
    fireEvent.change(await screen.findByRole("textbox", { name: "Full name" }), { target: { value: "!!" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    expect(await screen.findByText("That is not a name")).toBeInTheDocument();
    expect(screen.getByText("Nothing was saved. Please correct the fields marked below.")).toBeInTheDocument();
  });

  it("clears a refusal once the person edits the control it was about", async () => {
    stub([ profile() ], { status: "error", forbidden: false, changed: [], refused: { fullName: "No" } });

    render(<UserProfile />);
    const name = await screen.findByRole("textbox", { name: "Full name" });
    fireEvent.change(name, { target: { value: "!!" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByText("No")).toBeInTheDocument();

    fireEvent.change(name, { target: { value: "Jane Roe" } });

    await waitFor(() => { expect(screen.queryByText("No")).not.toBeInTheDocument(); });
  });

  it("reports a refusal about the person, which belongs to no control at all", async () => {
    stub([ profile() ], { status: "error", forbidden: true, changed: [], refused: {} });

    render(<UserProfile />);
    fireEvent.change(await screen.findByRole("textbox", { name: "Full name" }), { target: { value: "Jane Roe" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    expect(await screen.findByText("You are not allowed to change this profile.")).toBeInTheDocument();
  });

  it("reports a save that failed outright", async () => {
    // A 500 is ambiguous to the authenticated fetch -- Sling reports a write with an expired session
    // that way -- so it probes the session before deciding. Answering that probe with a live session
    // is what makes this a server error rather than a sign-in prompt.
    vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => {
      if (url.includes("sessionInfo")) {
        return Promise.resolve({ ok: true, status: 200, url: "", json: () => Promise.resolve({ userID: "jdoe" }) } as unknown as Response);
      }
      return init?.method === "POST"
        ? Promise.resolve({ ok: false, status: 500, url: "", json: () => Promise.reject(new Error("x")) } as unknown as Response)
        : Promise.resolve({ ok: true, status: 200, url: "", json: () => Promise.resolve(profile()) } as unknown as Response);
    }));

    render(<UserProfile />);
    fireEvent.change(await screen.findByRole("textbox", { name: "Full name" }), { target: { value: "Jane Roe" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

    expect(await screen.findByText(/Could not save your profile/)).toBeInTheDocument();
  });

  it("puts everything back the way it was recorded when the changes are discarded", async () => {
    stub([ profile() ]);

    render(<UserProfile />);
    const name = await screen.findByRole("textbox", { name: "Full name" });
    fireEvent.change(name, { target: { value: "Jane Roe" } });
    fireEvent.click(screen.getByRole("button", { name: "Discard changes" }));

    expect(name).toHaveValue("Jane Doe");
    expect(screen.getByRole("button", { name: "Save changes" })).toBeDisabled();
  });

  it("shows no initials at all when there is no name to take them from", async () => {
    stub([ profile({ account: "", fields: [ field({ name: "fullName", label: "Full name" }) ] }) ]);

    render(<UserProfile />);

    // The heading is empty, and so is the avatar: inventing an initial from nothing would be worse
    await waitFor(() => { expect(screen.getByRole("textbox", { name: "Full name" })).toBeInTheDocument(); });
    expect(screen.queryByText(/^[A-Z]{1,2}$/)).not.toBeInTheDocument();
  });

  it("lets the person dismiss the confirmation once they have seen it", async () => {
    stub([ profile() ]);

    render(<UserProfile />);
    fireEvent.change(await screen.findByRole("textbox", { name: "Full name" }), { target: { value: "Jane Roe" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByText("Your profile has been saved.")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Close" }));

    await waitFor(() => {
      expect(screen.queryByText("Your profile has been saved.")).not.toBeInTheDocument();
    });
  });

  it("lets the person dismiss a refusal that was about them", async () => {
    stub([ profile() ], { status: "error", forbidden: true, changed: [], refused: {} });

    render(<UserProfile />);
    fireEvent.change(await screen.findByRole("textbox", { name: "Full name" }), { target: { value: "Jane Roe" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    expect(await screen.findByText("You are not allowed to change this profile.")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Close" }));

    await waitFor(() => {
      expect(screen.queryByText("You are not allowed to change this profile.")).not.toBeInTheDocument();
    });
  });

  it("waits with a spinner rather than an empty page", () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise(() => undefined)));

    render(<UserProfile />);

    expect(screen.getByText("Loading your profile")).toBeInTheDocument();
  });

  it("offers to try again when the profile cannot be loaded", async () => {
    const fetcher = vi.fn(() => Promise.resolve({ ok: false, status: 503, url: "" } as unknown as Response));
    vi.stubGlobal("fetch", fetcher);

    render(<UserProfile />);
    expect(await screen.findByText(/Could not load your profile/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    await waitFor(() => { expect(fetcher.mock.calls.length).toBeGreaterThan(1); });
  });
});
