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

import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import NewSubmissionDialog, { schemaChoices } from "@iap/submissions/NewSubmissionDialog";

// A /Schemas tree as the serializer returns it at depth 2: the homepage's own properties, its
// schemas under their node names, and each schema's versions under theirs.
const SCHEMAS = {
  "jcr:primaryType": "sch:SchemasHomepage",
  "@path": "/Schemas",
  "@name": "Schemas",
  "timeOffRequest": {
    "@path": "/Schemas/timeOffRequest",
    "@name": "timeOffRequest",
    "title": "Time off request",
    "active": true,
    "v1": {
      "@path": "/Schemas/timeOffRequest/v1",
      "@name": "v1",
      "version": "1.0",
      "description": "Asking for a day off",
      "active": true,
    },
  },
};

function jsonResponse(body: unknown, init: { ok?: boolean; status?: number } = {}) {
  return {
    ok: init.ok ?? true,
    status: init.status ?? 200,
    redirected: false,
    url: "",
    json: () => Promise.resolve(body),
  };
}

function schemasFetch() {
  return vi.fn(() => Promise.resolve(jsonResponse(SCHEMAS)));
}

describe("schemaChoices", () => {
  it("offers the active version of each active schema", () => {
    expect(schemaChoices(SCHEMAS)).toEqual([ {
      path: "/Schemas/timeOffRequest/v1",
      title: "Time off request",
      version: "1.0",
      description: "Asking for a day off",
    } ]);
  });

  it("offers nothing for a retired schema, whatever its versions say", () => {
    // Both halves have to be open, which is the rule the server enforces when the submission is
    // actually raised; offering the choice anyway would just move the refusal later
    const retired = { s: { ...SCHEMAS.timeOffRequest, active: false } };

    expect(schemaChoices(retired)).toEqual([]);
  });

  it("offers nothing for a live schema whose versions are all retired", () => {
    const noVersion = { s: { ...SCHEMAS.timeOffRequest, v1: { ...SCHEMAS.timeOffRequest.v1, active: false } } };

    expect(schemaChoices(noVersion)).toEqual([]);
  });

  it("falls back to the node name for a schema with no title", () => {
    const untitled = { s: { ...SCHEMAS.timeOffRequest, title: undefined } };

    expect(schemaChoices(untitled)[0].title).toBe("timeOffRequest");
  });

  it("ignores the homepage's own properties, which are not schemas", () => {
    expect(schemaChoices({ "jcr:primaryType": "sch:SchemasHomepage", "count": 3 })).toEqual([]);
  });

  it("offers a nameless schema rather than dropping it", () => {
    // Both the title and the node name are mandatory in practice; the point is that content which
    // somehow lacks them is still offered, since a choice missing its label beats a missing choice
    const nameless = { s: { ...SCHEMAS.timeOffRequest, title: undefined, "@name": undefined } };

    expect(schemaChoices(nameless)[0]).toMatchObject({ title: "", path: "/Schemas/timeOffRequest/v1" });
  });

  it("offers a version with no label", () => {
    const unlabelled = { s: { ...SCHEMAS.timeOffRequest, v1: { ...SCHEMAS.timeOffRequest.v1, version: undefined } } };

    expect(schemaChoices(unlabelled)[0]).toMatchObject({ version: "" });
  });
});

describe("NewSubmissionDialog", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("lists what is open for submissions, with the version that will be answered", async () => {
    const fetchMock = schemasFetch();
    vi.stubGlobal("fetch", fetchMock);

    render(<NewSubmissionDialog onClose={() => {}} onCreated={() => {}} />);

    expect(await screen.findByText("Time off request 1.0")).toBeInTheDocument();
    expect(screen.getByText("Asking for a day off")).toBeInTheDocument();
    // Dereferencing off: each version references the whole workflow it freezes, and none of it is read here
    expect(fetchMock).toHaveBeenCalledWith("/Schemas.2.-dereference.json");
  });

  it("says when nothing is open for submissions, rather than showing an empty dialog", async () => {
    // Indistinguishable from a dialog that failed to load if it is left blank
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse({}))));

    render(<NewSubmissionDialog onClose={() => {}} onCreated={() => {}} />);

    expect(await screen.findByText("Nothing is currently open for submissions.")).toBeInTheDocument();
  });

  it("reports a list that would not load", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse({}, { ok: false, status: 503 }))));

    render(<NewSubmissionDialog onClose={() => {}} onCreated={() => {}} />);

    expect(await screen.findByText(/could not be loaded \(503\)/)).toBeInTheDocument();
  });

  it("raises the submission through the /Submissions endpoint and reports where it went", async () => {
    const created = vi.fn();
    const fetchMock = vi.fn((url: string) => Promise.resolve(url === "/Submissions"
      ? { ...jsonResponse({}), redirected: true, url: "http://localhost/Submissions/aLongWeekend" }
      : jsonResponse(SCHEMAS)));
    vi.stubGlobal("fetch", fetchMock);

    render(<NewSubmissionDialog onClose={() => {}} onCreated={created} />);
    await userEvent.click(await screen.findByRole("radio"));
    await userEvent.type(screen.getByLabelText(/Title/), "A long weekend");
    await userEvent.click(screen.getByRole("button", { name: "Create" }));

    await waitFor(() => expect(created).toHaveBeenCalledWith("/Submissions/aLongWeekend"));
    const [ , options ] = fetchMock.mock.calls[1] as unknown as [ string, { method: string; body: URLSearchParams } ];
    expect(options.method).toBe("POST");
    // The two things the system workflow that raises a submission asks for, and nothing else
    expect(options.body.get("title")).toBe("A long weekend");
    expect(options.body.get("schemaVersion")).toBe("/Schemas/timeOffRequest/v1");
  });

  it("cannot be submitted until both answers the workflow needs are given", async () => {
    vi.stubGlobal("fetch", schemasFetch());

    render(<NewSubmissionDialog onClose={() => {}} onCreated={() => {}} />);
    await screen.findByText("Time off request 1.0");
    const create = screen.getByRole("button", { name: "Create" });

    expect(create).toBeDisabled();
    await userEvent.click(screen.getByRole("radio"));
    expect(create).toBeDisabled();
    // Whitespace is not a title
    await userEvent.type(screen.getByLabelText(/Title/), "   ");
    expect(create).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/Title/), "Named");
    expect(create).toBeEnabled();
  });

  it("shows the engine's own reason for refusing", async () => {
    // A refusal carries why — no applicable workflow, not allowed, or a payload it will not take —
    // and repeating that verbatim beats inventing a generic message over the top of it
    const fetchMock = vi.fn((url: string) => Promise.resolve(url === "/Submissions"
      ? jsonResponse({ error: "The user is not allowed to perform this action" }, { ok: false, status: 403 })
      : jsonResponse(SCHEMAS)));
    vi.stubGlobal("fetch", fetchMock);

    render(<NewSubmissionDialog onClose={() => {}} onCreated={() => {}} />);
    await userEvent.click(await screen.findByRole("radio"));
    await userEvent.type(screen.getByLabelText(/Title/), "Something");
    await userEvent.click(screen.getByRole("button", { name: "Create" }));

    expect(await screen.findByText("The user is not allowed to perform this action")).toBeInTheDocument();
  });

  it("falls back to the status when a refusal carries no reason", async () => {
    const fetchMock = vi.fn((url: string) => Promise.resolve(url === "/Submissions"
      ? { ...jsonResponse({}, { ok: false, status: 409 }), json: () => Promise.reject(new Error("no body")) }
      : jsonResponse(SCHEMAS)));
    vi.stubGlobal("fetch", fetchMock);

    render(<NewSubmissionDialog onClose={() => {}} onCreated={() => {}} />);
    await userEvent.click(await screen.findByRole("radio"));
    await userEvent.type(screen.getByLabelText(/Title/), "Something");
    await userEvent.click(screen.getByRole("button", { name: "Create" }));

    expect(await screen.findByText(/could not be raised \(409\)/)).toBeInTheDocument();
  });

  it("reports an empty path when the engine created nothing to open", async () => {
    // A 200 rather than a redirect means the delivery was accepted without creating anything, so
    // there is nowhere to send the submitter
    const created = vi.fn();
    const fetchMock = vi.fn((url: string) => Promise.resolve(
      url === "/Submissions" ? jsonResponse({}) : jsonResponse(SCHEMAS)));
    vi.stubGlobal("fetch", fetchMock);

    render(<NewSubmissionDialog onClose={() => {}} onCreated={created} />);
    await userEvent.click(await screen.findByRole("radio"));
    await userEvent.type(screen.getByLabelText(/Title/), "Something");
    await userEvent.click(screen.getByRole("button", { name: "Create" }));

    await waitFor(() => expect(created).toHaveBeenCalledWith(""));
  });

  it("reports a load that failed with something that is not an Error", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject("the network went away")));

    render(<NewSubmissionDialog onClose={() => {}} onCreated={() => {}} />);

    expect(await screen.findByText("the network went away")).toBeInTheDocument();
  });

  it("reports a refusal that failed with something that is not an Error", async () => {
    const fetchMock = vi.fn((url: string) => url === "/Submissions"
      ? Promise.reject("the network went away")
      : Promise.resolve(jsonResponse(SCHEMAS)));
    vi.stubGlobal("fetch", fetchMock);

    render(<NewSubmissionDialog onClose={() => {}} onCreated={() => {}} />);
    await userEvent.click(await screen.findByRole("radio"));
    await userEvent.type(screen.getByLabelText(/Title/), "Something");
    await userEvent.click(screen.getByRole("button", { name: "Create" }));

    expect(await screen.findByText("the network went away")).toBeInTheDocument();
  });

  it("keeps quiet when it is closed while still loading", async () => {
    // The dialog is unmounted the moment it is closed, so a list that arrives afterwards has
    // nowhere to go; it must be dropped rather than set on a component that is gone
    let deliver: (value: unknown) => void = () => {};
    const inFlight = new Promise(resolve => {
      deliver = resolve;
    });
    vi.stubGlobal("fetch", vi.fn(() => inFlight));
    const warn = vi.spyOn(console, "error").mockImplementation(() => {});

    const { unmount } = render(<NewSubmissionDialog onClose={() => {}} onCreated={() => {}} />);
    unmount();
    deliver(jsonResponse(SCHEMAS));
    await inFlight;

    expect(warn).not.toHaveBeenCalled();
    warn.mockRestore();
  });

  it("closes without raising anything when cancelled", async () => {
    const close = vi.fn();
    vi.stubGlobal("fetch", schemasFetch());

    render(<NewSubmissionDialog onClose={close} onCreated={() => {}} />);
    await screen.findByText("Time off request 1.0");
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(close).toHaveBeenCalled();
  });
});
