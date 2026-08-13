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

import SchemaVersionSelect from "@iap/categories/SchemaVersionSelect";
import { SESSION_INFO_URL } from "@iap/frontend-commons/reLogin";

// The /Schemas serialization: two schemas, one titled and one falling back to its node name, plus
// the entries the picker has to skip -- a non-schema node, and a version with no identifier to
// reference it by.
const schemasJson = {
  "jcr:primaryType": "sch:SchemasHomepage",
  "notASchema": { "jcr:primaryType": "nt:unstructured" },
  "basic": {
    "jcr:primaryType": "sch:Schema",
    "title": "Basic study",
    "notAVersion": { "jcr:primaryType": "nt:unstructured" },
    "v1": { "jcr:primaryType": "sch:SchemaVersion", "jcr:uuid": "uuid-1", "version": "1.0", "active": true },
    "v2": { "jcr:primaryType": "sch:SchemaVersion", "jcr:uuid": "uuid-2", "version": "2.0", "active": false },
    "unreferenceable": { "jcr:primaryType": "sch:SchemaVersion", "version": "3.0" },
  },
  "untitled": {
    "jcr:primaryType": "sch:Schema",
    "v1": { "jcr:primaryType": "sch:SchemaVersion", "jcr:uuid": "uuid-3" },
  },
  "empty": { "jcr:primaryType": "sch:Schema" },
};

// The answer carries the `url` it came back from because the request goes through
// useAuthenticatedFetch, which reads it to recognise the login page an expired session lands on.
const stubSchemas = (body: unknown = schemasJson) =>
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve({
    ok: true,
    status: 200,
    url,
    json: () => Promise.resolve(body),
  } as unknown as Response)));

const renderSelect = (value = "") => {
  const onChange = vi.fn();
  render(<SchemaVersionSelect value={value} onChange={onChange} />);
  return { onChange };
};

// The options only exist once the Select's menu is open
const openMenu = async () => {
  await waitFor(() => { expect(screen.getByRole("combobox")).toBeEnabled(); });
  fireEvent.mouseDown(screen.getByRole("combobox"));
  return screen.findByRole("listbox");
};

describe("SchemaVersionSelect", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("waits for the schemas before letting anything be picked", () => {
    stubSchemas();

    renderSelect();

    expect(screen.getByRole("combobox")).toHaveAttribute("aria-disabled", "true");
  });

  it("asks for the schemas without inlining the workflows they reference", async () => {
    stubSchemas();

    renderSelect();

    // Asserted on the URL alone: the request goes through useAuthenticatedFetch, which passes an
    // init argument of its own along
    await waitFor(() => {
      expect(vi.mocked(fetch).mock.calls[0][0]).toBe("/Schemas.deep.-dereference.json");
    });
  });

  it("groups the versions under their schema, and marks the inactive ones", async () => {
    stubSchemas();
    renderSelect();

    const listbox = await openMenu();

    expect(within(listbox).getByText("Basic study")).toBeInTheDocument();
    expect(within(listbox).getByText("v1.0")).toBeInTheDocument();
    expect(within(listbox).getByText("v2.0 (inactive)")).toBeInTheDocument();
  });

  it("falls back to the node name for a schema with no title, and to '?' for a version with no label", async () => {
    stubSchemas();
    renderSelect();

    const listbox = await openMenu();

    expect(within(listbox).getByText("untitled")).toBeInTheDocument();
    // No `active: true` either, so it reads as inactive
    expect(within(listbox).getByText("v? (inactive)")).toBeInTheDocument();
  });

  it("leaves out schemas with nothing referenceable in them", async () => {
    stubSchemas();
    renderSelect();

    const listbox = await openMenu();

    // "empty" has no versions at all, and basic's identifier-less v3.0 cannot be bound to
    expect(within(listbox).queryByText("empty")).not.toBeInTheDocument();
    expect(within(listbox).queryByText("v3.0")).not.toBeInTheDocument();
  });

  it("offers leaving the category unbound", async () => {
    stubSchemas();
    const { onChange } = renderSelect("uuid-1");

    const listbox = await openMenu();
    fireEvent.click(within(listbox).getByText(/None/));

    expect(onChange).toHaveBeenCalledWith("");
  });

  it("reports the version that was picked", async () => {
    stubSchemas();
    const { onChange } = renderSelect();

    const listbox = await openMenu();
    fireEvent.click(within(listbox).getByText("v2.0 (inactive)"));

    expect(onChange).toHaveBeenCalledWith("uuid-2");
  });

  it("keeps a binding it cannot account for selectable", async () => {
    stubSchemas();
    renderSelect("uuid-gone");

    const listbox = await openMenu();

    // Its schema is no longer under /Schemas, but dropping the value would silently rebind the
    // category the next time the dialog is saved
    expect(within(listbox).getByText("Current binding")).toBeInTheDocument();
  });

  it("says so when the schemas cannot be loaded", async () => {
    vi.spyOn(console, "error").mockImplementation(() => { /* keep the output quiet */ });
    // The session endpoint answers that the session is live, which is what makes this 500 the
    // server's own problem rather than a lapsed session to sign back in for
    vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve((url === SESSION_INFO_URL
      ? { ok: true, status: 200, url, json: () => Promise.resolve({ userID: "admin" }) }
      : { ok: false, status: 500, url }) as unknown as Response)));

    renderSelect();

    expect(await screen.findByText("The available schemas could not be loaded")).toBeInTheDocument();
    expect(screen.getByRole("combobox")).toHaveAttribute("aria-disabled", "true");
  });

  it("says so when the request fails outright", async () => {
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => { /* keep the output quiet */ });
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new Error("offline"))));

    renderSelect();

    expect(await screen.findByText("The available schemas could not be loaded")).toBeInTheDocument();
    expect(errorSpy).toHaveBeenCalledWith("Could not load the available schema versions", expect.any(Error));
  });
});
