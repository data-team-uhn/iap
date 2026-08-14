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

import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";

import CategoryManager from "@iap/categories/CategoryManager";
import { SESSION_INFO_URL } from "@iap/frontend-commons/reLogin";

// A tree with a branch (whose deletion must be blocked), a bound leaf, and a retired category.
const treeJson = {
  "jcr:primaryType": "cat:CategoriesHomepage",
  "Retrospective": {
    "jcr:primaryType": "cat:Category",
    "label": "Retrospective studies",
    "description": "Existing data or specimens only.",
    "retired": false,
    "RetrospectiveData": {
      "jcr:primaryType": "cat:Category",
      "label": "Retrospective Data Studies",
      "retired": false,
      "schemaVersion": {
        "jcr:primaryType": "sch:SchemaVersion",
        "jcr:uuid": "uuid-sv1",
        "version": "1.0",
        "@path": "/Schemas/basic/1.0",
      },
    },
  },
  "Paper": {
    "jcr:primaryType": "cat:Category",
    "label": "Paper submissions",
    "retired": true,
  },
};

// A server that answers everything, except the writes `refusing` picks out by their payload - which
// is how a test singles out one step of a save that takes more than one.
//
// The manager's requests go through useAuthenticatedFetch, which shows up here in two ways: every
// answer carries the `url` it came back from, since that is how the login page Sling redirects an
// expired session to is recognised; and the session endpoint answers that the session is live, so
// that a 500 a test asks for is taken as the server's own problem rather than a lapsed session.
const stubFetch = (refusing: (body: string) => boolean = () => false) =>
  vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => {
    if (url === SESSION_INFO_URL) {
      return Promise.resolve({
        ok: true, status: 200, url, json: () => Promise.resolve({ userID: "admin" }),
      } as unknown as Response);
    }
    const body = init?.body;
    if (body instanceof URLSearchParams && refusing(body.toString())) {
      return Promise.resolve({ ok: false, status: 403, statusText: "Forbidden", url } as unknown as Response);
    }
    return Promise.resolve({
      ok: true, status: 200, statusText: "OK", url,
      json: () => Promise.resolve(url.startsWith("/Schemas")
        ? { "jcr:primaryType": "sch:SchemasHomepage" }
        : treeJson),
      headers: { get: () => null },
    } as unknown as Response);
  }));

// A server that answers everything with the same failure, while reporting the session as live: what
// makes the failure the server's own rather than a lapsed session to be recovered from.
const stubFailingFetch = (status: number, statusText: string) =>
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve((url === SESSION_INFO_URL
    ? { ok: true, status: 200, url, json: () => Promise.resolve({ userID: "admin" }) }
    : { ok: false, status, statusText, url }) as unknown as Response)));

afterEach(() => vi.unstubAllGlobals());

const renderManager = () => render(<MemoryRouter><CategoryManager /></MemoryRouter>);

describe("CategoryManager", () => {
  it("renders the category tree with nested rows", async () => {
    stubFetch();
    renderManager();

    expect(await screen.findByText("Retrospective studies")).toBeInTheDocument();
    expect(screen.getByText("Retrospective Data Studies")).toBeInTheDocument();
    expect(screen.getByText("Paper submissions")).toBeInTheDocument();
    expect(screen.getByText("Existing data or specimens only.")).toBeInTheDocument();
  });

  it("marks retired categories and displays schema bindings", async () => {
    stubFetch();
    renderManager();

    expect(await screen.findByText("Retired")).toBeInTheDocument();
    expect(screen.getByText("Schema: basic v1.0")).toBeInTheDocument();
  });

  it("blocks deleting a category that still has subcategories", async () => {
    stubFetch();
    renderManager();

    expect(await screen.findByRole("button", { name: "Delete Retrospective studies" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Delete Paper submissions" })).toBeEnabled();
  });

  it("opens the creation dialog from the main action", async () => {
    stubFetch();
    renderManager();
    await screen.findByText("Retrospective studies");

    fireEvent.click(screen.getByRole("button", { name: "New category" }));

    expect(await screen.findByRole("heading", { name: "New category" })).toBeInTheDocument();
    expect(screen.getByLabelText(/Label/)).toBeInTheDocument();
  });

  it("offers retiring instead when the server refuses a deletion", async () => {
    stubFetch();
    const fetchMock = vi.mocked(fetch);
    renderManager();
    await screen.findByText("Paper submissions");

    fireEvent.click(screen.getByRole("button", { name: "Delete Paper submissions" }));
    // The confirmation dialog appears; the deletion endpoint then refuses, naming what refers to it
    fetchMock.mockResolvedValueOnce({
      ok: false, status: 409, statusText: "Conflict",
      json: () => Promise.resolve({
        "status": "referenced",
        "status.message": "This item is referenced in 2 submissions (S-1, S-2).",
      }),
    } as unknown as Response);
    fireEvent.click(await screen.findByRole("button", { name: "Delete" }));

    expect(await screen.findByText(/referenced in 2 submissions/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retire instead" })).toBeInTheDocument();
  });

  // The POSTs the manager sends, matched by the payload rather than by call order: every mutation
  // is followed by a reload, so the request under test is never the only one in flight.
  const postsMatching = (predicate: (body: string) => boolean) =>
    vi.mocked(fetch).mock.calls.filter(([, init]) => {
      const body = init?.body;
      return body instanceof URLSearchParams && predicate(body.toString());
    });

  const postTo = (path: string) =>
    vi.mocked(fetch).mock.calls.filter(([url, init]) => url === path && !!init?.method);

  describe("saving a category", () => {
    it("creates one under the parent the tree row names", async () => {
      stubFetch();
      renderManager();
      await screen.findByText("Retrospective studies");

      fireEvent.click(screen.getByRole("button", { name: "Add subcategory to Paper submissions" }));
      fireEvent.change(await screen.findByLabelText(/Label/), { target: { value: "Posters" } });
      fireEvent.click(screen.getByRole("button", { name: "Create" }));

      await waitFor(() => { expect(postTo("/Categories/Paper/")).toHaveLength(1); });
      expect(postsMatching(body => body.includes("label=Posters"))).not.toHaveLength(0);
    });

    it("frees the parent's schema version before giving it a subcategory", async () => {
      stubFetch();
      renderManager();
      await screen.findByText("Retrospective Data Studies");

      fireEvent.click(screen.getByRole("button", { name: "Add subcategory to Retrospective Data Studies" }));
      expect(await screen.findByText(/will lose its schema version/)).toBeInTheDocument();
      fireEvent.change(screen.getByLabelText(/Label/), { target: { value: "Chart reviews" } });
      fireEvent.click(screen.getByRole("button", { name: "Create" }));

      await waitFor(() => {
        expect(postTo("/Categories/Retrospective/RetrospectiveData/")).toHaveLength(1);
      });
      const sent = vi.mocked(fetch).mock.calls;
      const unbound = sent.findIndex(([url, init]) => url === "/Categories/Retrospective/RetrospectiveData"
        && init?.body instanceof URLSearchParams && init.body.has("schemaVersion@Delete"));
      const created = sent.findIndex(([url]) => url === "/Categories/Retrospective/RetrospectiveData/");
      expect(unbound).toBeGreaterThanOrEqual(0);
      // Unbinding first is what leaves a failure recoverable: a leaf that lost its binding can be
      // given it back, while a non-leaf that kept one is not offered the picker at all
      expect(unbound).toBeLessThan(created);
    });

    it("leaves an unbound parent's fields alone when it gains a subcategory", async () => {
      stubFetch();
      renderManager();
      await screen.findByText("Paper submissions");

      fireEvent.click(screen.getByRole("button", { name: "Add subcategory to Paper submissions" }));
      fireEvent.change(await screen.findByLabelText(/Label/), { target: { value: "Posters" } });
      fireEvent.click(screen.getByRole("button", { name: "Create" }));

      await waitFor(() => { expect(postTo("/Categories/Paper/")).toHaveLength(1); });
      expect(postTo("/Categories/Paper")).toHaveLength(0);
      expect(screen.queryByText(/will lose its schema version/)).not.toBeInTheDocument();
    });

    it("updates one in place when its parent is unchanged", async () => {
      stubFetch();
      renderManager();
      await screen.findByText("Paper submissions");

      fireEvent.click(screen.getByRole("button", { name: "Edit Paper submissions" }));
      fireEvent.change(await screen.findByLabelText(/Label/), { target: { value: "Paper forms" } });
      fireEvent.click(screen.getByRole("button", { name: "Save" }));

      await waitFor(() => { expect(postTo("/Categories/Paper")).not.toHaveLength(0); });
      expect(postsMatching(body => body.includes("label=Paper+forms"))).not.toHaveLength(0);
      // Nothing was moved: no :operation=move went out
      expect(postsMatching(body => body.includes("operation=move"))).toHaveLength(0);
    });

    it("moves one when a different parent is picked", async () => {
      stubFetch();
      renderManager();
      await screen.findByText("Paper submissions");
      fireEvent.click(screen.getByRole("button", { name: "Edit Paper submissions" }));

      // Re-parent it under the other top-level category
      fireEvent.mouseDown(await screen.findByRole("combobox", { name: /Parent category/ }));
      fireEvent.click(await screen.findByRole("option", { name: "Retrospective studies" }));
      fireEvent.click(screen.getByRole("button", { name: "Save" }));

      await waitFor(() => {
        expect(postsMatching(body => body.includes("operation=move"))).not.toHaveLength(0);
      });
      const move = postsMatching(body => body.includes("operation=move"))[0];
      expect((move[1]!.body as URLSearchParams).get(":dest")).toBe("/Categories/Retrospective/");
    });

    // The two writes an edit can take are reported separately, because the first one having landed
    // is what the user has to know: the tree behind the dialog already shows the new label
    it("says so when the fields were saved but the move was refused", async () => {
      stubFetch(body => body.includes("operation=move"));
      renderManager();
      await screen.findByText("Paper submissions");
      fireEvent.click(screen.getByRole("button", { name: "Edit Paper submissions" }));
      fireEvent.change(await screen.findByLabelText(/Label/), { target: { value: "Paper forms" } });
      fireEvent.mouseDown(screen.getByRole("combobox", { name: /Parent category/ }));
      fireEvent.click(await screen.findByRole("option", { name: "Retrospective studies" }));

      fireEvent.click(screen.getByRole("button", { name: "Save" }));

      const dialog = await screen.findByRole("dialog");
      expect(await within(dialog).findByText(
        "The changes were saved, but the category could not be moved to Retrospective studies"
      )).toBeInTheDocument();
      // The fields did go through, and the dialog stays up rather than closing on a half-done edit
      expect(postsMatching(body => body.includes("label=Paper+forms"))).not.toHaveLength(0);
    });

    it("closes the dialog without saving anything when cancelled", async () => {
      stubFetch();
      renderManager();
      await screen.findByText("Paper submissions");
      fireEvent.click(screen.getByRole("button", { name: "Edit Paper submissions" }));
      await screen.findByLabelText(/Label/);

      fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

      await waitFor(() => { expect(screen.queryByLabelText(/Label/)).not.toBeInTheDocument(); });
      expect(postsMatching(() => true)).toHaveLength(0);
    });
  });

  describe("acting on a row", () => {
    it("retires a category once the retirement is confirmed", async () => {
      stubFetch();
      renderManager();
      await screen.findByText("Retrospective studies");

      fireEvent.click(screen.getByRole("button", { name: "Retire Retrospective studies" }));

      // The row action only asks; the effect is spelled out because it lands on submitters
      expect(await screen.findByRole("heading", { name: "Retire Retrospective studies?" })).toBeInTheDocument();
      expect(screen.getByText(/no longer be available for new submissions/)).toBeInTheDocument();
      expect(postsMatching(body => body.includes("retired=true"))).toHaveLength(0);

      fireEvent.click(screen.getByRole("button", { name: "Retire" }));

      await waitFor(() => {
        expect(postsMatching(body => body.includes("retired=true"))).not.toHaveLength(0);
      });
    });

    it("abandons a retirement that is called off", async () => {
      stubFetch();
      renderManager();
      await screen.findByText("Retrospective studies");
      fireEvent.click(screen.getByRole("button", { name: "Retire Retrospective studies" }));
      await screen.findByRole("button", { name: "Retire" });

      fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

      await waitFor(() => {
        expect(screen.queryByRole("button", { name: "Retire" })).not.toBeInTheDocument();
      });
      expect(postsMatching(body => body.includes("retired=true"))).toHaveLength(0);
    });

    // Unretiring is the confirmation's undo, so it acts directly
    it("unretires a retired category without asking", async () => {
      stubFetch();
      renderManager();
      await screen.findByText("Paper submissions");

      fireEvent.click(screen.getByRole("button", { name: "Unretire Paper submissions" }));

      await waitFor(() => {
        expect(postsMatching(body => body.includes("retired=false"))).not.toHaveLength(0);
      });
    });

    it("reorders a category among its siblings", async () => {
      stubFetch();
      renderManager();
      await screen.findByText("Paper submissions");

      fireEvent.click(screen.getByRole("button", { name: "Move Paper submissions up" }));

      await waitFor(() => {
        expect(postsMatching(body => body.includes("order=before+Retrospective"))).not.toHaveLength(0);
      });
    });

    it("deletes a category once the deletion is confirmed", async () => {
      stubFetch();
      renderManager();
      await screen.findByText("Paper submissions");
      fireEvent.click(screen.getByRole("button", { name: "Delete Paper submissions" }));

      fireEvent.click(await screen.findByRole("button", { name: "Delete" }));

      await waitFor(() => {
        expect(vi.mocked(fetch).mock.calls.filter(([url, init]) =>
          url === "/Categories/Paper" && init?.method === "DELETE")).toHaveLength(1);
      });
    });

    it("retires a category that could not be deleted", async () => {
      stubFetch();
      const fetchMock = vi.mocked(fetch);
      renderManager();
      await screen.findByText("Paper submissions");
      fireEvent.click(screen.getByRole("button", { name: "Delete Paper submissions" }));
      fetchMock.mockResolvedValueOnce({ ok: false, status: 409, statusText: "Conflict" } as unknown as Response);
      fireEvent.click(await screen.findByRole("button", { name: "Delete" }));

      fireEvent.click(await screen.findByRole("button", { name: "Retire instead" }));

      await waitFor(() => {
        expect(postsMatching(body => body.includes("retired=true"))).not.toHaveLength(0);
      });
    });

    it("abandons a deletion that is called off", async () => {
      stubFetch();
      renderManager();
      await screen.findByText("Paper submissions");
      fireEvent.click(screen.getByRole("button", { name: "Delete Paper submissions" }));
      await screen.findByRole("button", { name: "Delete" });

      fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

      await waitFor(() => {
        expect(screen.queryByRole("button", { name: "Delete" })).not.toBeInTheDocument();
      });
      expect(postsMatching(body => body.includes("operation=delete"))).toHaveLength(0);
    });
  });

  describe("when something goes wrong", () => {
    // The row actions that act immediately - reordering, and unretiring - have no dialog of their
    // own to report back in, so they report over the tree. The ones that ask first report inside
    // the dialog the user is already looking at.
    it("names what did not happen when a row action is refused, and lets the report be dismissed", async () => {
      stubFetch();
      const fetchMock = vi.mocked(fetch);
      renderManager();
      await screen.findByText("Paper submissions");

      fetchMock.mockResolvedValueOnce({ ok: false, status: 403, statusText: "Forbidden" } as unknown as Response);
      fireEvent.click(screen.getByRole("button", { name: "Move Paper submissions up" }));

      const notice = await screen.findByRole("alert");
      // Which category, and what did not happen to it - neither of which the row action itself said.
      // "Up", not just "moved": moving to another parent is a different action with the same verb.
      expect(notice).toHaveTextContent("Paper submissions could not be moved up");
      // Why, in the user's terms, with the status kept for whoever has to report it
      expect(notice).toHaveTextContent("You do not have permission to do this. (HTTP 403)");
      // Reported over the tree, not in front of it
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

      fireEvent.click(screen.getByRole("button", { name: "Dismiss" }));

      await waitFor(() => { expect(screen.queryByRole("alert")).not.toBeInTheDocument(); });
    });

    it("reports the other direction as itself", async () => {
      stubFetch(body => body.includes("order=after"));
      renderManager();
      await screen.findByText("Retrospective studies");

      fireEvent.click(screen.getByRole("button", { name: "Move Retrospective studies down" }));

      expect(await screen.findByRole("alert"))
        .toHaveTextContent("Retrospective studies could not be moved down");
    });

    it("offers the refused action again, and reports a retry that fails in turn", async () => {
      stubFetch();
      const fetchMock = vi.mocked(fetch);
      renderManager();
      await screen.findByText("Paper submissions");
      fetchMock.mockResolvedValueOnce({ ok: false, status: 500, statusText: "Server Error" } as unknown as Response);
      fireEvent.click(screen.getByRole("button", { name: "Move Paper submissions up" }));
      await screen.findByRole("alert");

      // Refused a second time: the notice comes back rather than the offer going away
      fetchMock.mockResolvedValueOnce({ ok: false, status: 500, statusText: "Server Error" } as unknown as Response);
      fireEvent.click(screen.getByRole("button", { name: "Retry" }));

      await waitFor(() => {
        expect(postsMatching(body => body.includes("order=before+Retrospective"))).toHaveLength(2);
      });
      expect(await screen.findByRole("alert")).toHaveTextContent("Paper submissions could not be moved");
    });

    it("carries the action through when its retry is taken up", async () => {
      stubFetch();
      const fetchMock = vi.mocked(fetch);
      renderManager();
      await screen.findByText("Paper submissions");
      fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
      fireEvent.click(screen.getByRole("button", { name: "Unretire Paper submissions" }));
      await screen.findByRole("alert");

      fireEvent.click(screen.getByRole("button", { name: "Retry" }));

      await waitFor(() => {
        expect(postsMatching(body => body.includes("retired=false"))).toHaveLength(2);
      });
      await waitFor(() => { expect(screen.queryByRole("alert")).not.toBeInTheDocument(); });
    });

    it("reports a failure that was not an Error", async () => {
      stubFetch();
      const fetchMock = vi.mocked(fetch);
      renderManager();
      await screen.findByText("Paper submissions");

      fetchMock.mockRejectedValueOnce("connection reset");
      fireEvent.click(screen.getByRole("button", { name: "Unretire Paper submissions" }));

      expect(await screen.findByText(/connection reset/)).toBeInTheDocument();
    });

    it("blames the connection when the server cannot be reached at all", async () => {
      stubFetch();
      const fetchMock = vi.mocked(fetch);
      renderManager();
      await screen.findByText("Paper submissions");

      // What fetch rejects with when the request never completes
      fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
      fireEvent.click(screen.getByRole("button", { name: "Unretire Paper submissions" }));

      expect(await screen.findByText(/The server could not be reached/)).toBeInTheDocument();
      expect(screen.queryByText(/Failed to fetch/)).not.toBeInTheDocument();
    });

    it("reports a refused retirement inside the dialog that asked for it", async () => {
      stubFetch();
      const fetchMock = vi.mocked(fetch);
      renderManager();
      await screen.findByText("Retrospective studies");
      fireEvent.click(screen.getByRole("button", { name: "Retire Retrospective studies" }));

      fetchMock.mockResolvedValueOnce({ ok: false, status: 403, statusText: "Forbidden" } as unknown as Response);
      fireEvent.click(await screen.findByRole("button", { name: "Retire" }));

      // Reported where it was asked for, and ready to be tried again
      const dialog = await screen.findByRole("dialog");
      expect(within(dialog).getByText(/You do not have permission/)).toBeInTheDocument();
      expect(screen.getByRole("heading", { name: "Retire Retrospective studies?" })).toBeInTheDocument();
      // Not also over the tree: the dismissable notice belongs to the actions that act at once
      expect(screen.queryByRole("button", { name: "Dismiss" })).not.toBeInTheDocument();
    });

    // A load failure is reported in place rather than in a modal, because the modal would be a
    // dead end: there is nothing to acknowledge, only something to try again.
    it("reports a tree that could not be loaded, in place", async () => {
      stubFailingFetch(500, "Server Error");

      renderManager();

      const report = await screen.findByRole("alert");
      expect(report).toHaveTextContent("The categories could not be loaded");
      expect(report).toHaveTextContent("500");
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    it("reloads the tree when the load failure's Retry is used", async () => {
      stubFailingFetch(500, "Server Error");
      renderManager();
      await screen.findByRole("alert");

      // The next attempt succeeds
      stubFetch();
      fireEvent.click(screen.getByRole("button", { name: "Retry" }));

      expect(await screen.findByText("Retrospective studies")).toBeInTheDocument();
      await waitFor(() => { expect(screen.queryByRole("alert")).not.toBeInTheDocument(); });
    });

    it("invites the first category when there are none", async () => {
      vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve({
        ok: true, status: 200, statusText: "OK", url,
        json: () => Promise.resolve({ "jcr:primaryType": "cat:CategoriesHomepage" }),
        headers: { get: () => null },
      } as unknown as Response)));

      renderManager();

      expect(await screen.findByText(/No categories are defined yet/)).toBeInTheDocument();
    });
  });
});
