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

import { createVersion, createWorkflow, draftFromVersion, saveDiagram, updateWorkflow } from "@iap/workflows/workflowWrites";

// A stubbed fetch: the URL, and the request options an event carries.
type FetchStub = (url: string, options?: RequestInit) => Promise<Response>;

// An engine that runs every event it's given. One that created something answers with a redirect,
// which fetch follows on its own — so the caller sees a response whose final URL is the created
// entity, exactly as in the browser.
const acceptingFetch = (created = (url: string) => `${url.split(".")[0]}/created`) =>
  vi.fn<FetchStub>(url => Promise.resolve({
    ok: true,
    status: 200,
    redirected: true,
    url: `http://localhost:8080${created(url)}`,
    json: () => Promise.resolve({}),
  } as unknown as Response));

// An engine that ran the event but created nothing, which is every event but the two that do.
const completingFetch = () => vi.fn<FetchStub>(() => Promise.resolve({
  ok: true,
  status: 200,
  redirected: false,
  url: "http://localhost:8080/Workflows",
  json: () => Promise.resolve({ status: "completed" }),
} as unknown as Response));

// An engine that refused the event, explaining what it would not do.
const refusingFetch = (status: number, error?: string) => vi.fn<FetchStub>(() => Promise.resolve({
  ok: false,
  status,
  json: () => (error === undefined
    ? Promise.reject(new SyntaxError("Unexpected token '<'"))
    : Promise.resolve({ error })),
} as unknown as Response));

// The parameters one call was made with, as a plain object.
const paramsOf = (fetchUtil: ReturnType<typeof acceptingFetch>, call: number): Record<string, string> =>
  Object.fromEntries((fetchUtil.mock.calls[call][1]?.body as URLSearchParams).entries());

// The multipart body one call was made with.
const formOf = (fetchUtil: ReturnType<typeof acceptingFetch>, call: number): FormData =>
  fetchUtil.mock.calls[call][1]?.body as FormData;

describe("createWorkflow", () => {
  it("asks the homepage for a workflow and its first version in one event", async () => {
    const fetchUtil = acceptingFetch(() => "/Workflows/standardReview/1-0");

    const versionPath = await createWorkflow(fetchUtil, {
      homepage: "/Workflows",
      title: "Standard review",
      version: "1.0",
      description: "The first cut",
    });

    // One request, naming its event: a failure part-way can't leave a workflow with no version, and
    // nothing here names a node type or a node name -- the handlers derive both
    expect(fetchUtil).toHaveBeenCalledTimes(1);
    expect(fetchUtil.mock.calls[0][0]).toBe("/Workflows.create.json");
    const requested = formOf(fetchUtil, 0);
    expect(requested.get("title")).toBe("Standard review");
    expect(requested.get("version")).toBe("1.0");
    expect(requested.get("description")).toBe("The first cut");
    // The diagram travels with the request rather than following it, so a version with no diagram is
    // never a state anything can observe
    expect(requested.get("bpmn.xml")).toBeInstanceOf(File);

    expect(versionPath).toBe("/Workflows/standardReview/1-0");
  });

  it("leaves an empty description out rather than sending one", async () => {
    const fetchUtil = acceptingFetch();

    await createWorkflow(fetchUtil, { homepage: "/Workflows", title: "Bare", version: "1.0", description: "" });

    expect(formOf(fetchUtil, 0).get("description")).toBeNull();
  });

  it("complains rather than guessing when the engine does not say what it created", async () => {
    // Nothing here derives the node name -- the handler does, from the title -- so a caller that
    // guessed would be wrong as soon as two workflows wanted the same name
    const fetchUtil = completingFetch();

    await expect(createWorkflow(fetchUtil, {
      homepage: "/Workflows", title: "Standard review", version: "1.0", description: "",
    })).rejects.toThrow("did not say where");
  });

  it("reports the refusal in the words the engine used", async () => {
    const fetchUtil = refusingFetch(403, "You are not allowed to create workflows here");

    await expect(createWorkflow(fetchUtil, {
      homepage: "/Workflows", title: "Standard review", version: "1.0", description: "",
    })).rejects.toThrow("You are not allowed to create workflows here");
  });

  it("describes a refusal that explains nothing like any failed request", async () => {
    const fetchUtil = refusingFetch(403);

    await expect(createWorkflow(fetchUtil, {
      homepage: "/Workflows", title: "Standard review", version: "1.0", description: "",
    })).rejects.toThrow("You do not have permission to do this. (HTTP 403)");
  });
});

describe("createVersion", () => {
  it("asks the workflow for a draft, carrying the diagram it starts from", async () => {
    const fetchUtil = acceptingFetch();

    const path = await createVersion(fetchUtil, "/Workflows/review", { version: "2.0", description: "" });

    expect(fetchUtil.mock.calls[0][0]).toBe("/Workflows/review.createVersion.json");
    const body = formOf(fetchUtil, 0);
    expect(body.get("version")).toBe("2.0");
    expect(body.get("bpmn.xml")).toBeInstanceOf(File);
    // No lifecycle tag, and nothing saying the diagram owns the graph: both are what opening a version
    // means, and the definition that says so is where they are decided
    expect(body.get("tags")).toBeNull();
    expect(body.get("bpmnAuthoritative")).toBeNull();
    expect(path).toBe("/Workflows/review/created");
  });
});

describe("updateWorkflow", () => {
  it("sends the workflow's own save event, carrying its title", async () => {
    // Whether the workflow runs is not among its properties: that is read off its versions, and is
    // changed by activating one of them
    const fetchUtil = completingFetch();

    await updateWorkflow(fetchUtil, "/Workflows/review", { title: "Reviewed twice" });

    expect(fetchUtil.mock.calls[0][0]).toBe("/Workflows/review.save.json");
    expect(paramsOf(fetchUtil, 0)).toEqual({ title: "Reviewed twice" });
  });

  it("reports the refusal in the words the engine used", async () => {
    const fetchUtil = refusingFetch(400, "A title is required");

    await expect(updateWorkflow(fetchUtil, "/Workflows/review", { title: "  " }))
      .rejects.toThrow("A title is required");
  });
});

describe("saveDiagram", () => {
  it("sends the version's own save event, carrying the diagram", async () => {
    const fetchUtil = completingFetch();

    await saveDiagram(fetchUtil, "/Workflows/review/1-0", "<bpmn:definitions/>");

    expect(fetchUtil.mock.calls[0][0]).toBe("/Workflows/review/1-0.save.json");
    const body = formOf(fetchUtil, 0);
    expect(body.get("bpmn.xml")).toBeInstanceOf(File);
    // A payload key the handler looks up, not a Sling POST servlet path with a type hint: where the
    // diagram ends up in the repository is the handler's business
    expect(body.get("./bpmn.xml@TypeHint")).toBeNull();
  });

  it("reports a version the server will not let be edited", async () => {
    // The refusal now comes from the repository rather than only from the editor declining to open
    const fetchUtil = refusingFetch(409, "Only a draft may be edited, and this version is active");

    await expect(saveDiagram(fetchUtil, "/Workflows/review/1-0", "<bpmn:definitions/>"))
      .rejects.toThrow("Only a draft may be edited, and this version is active");
  });
});

describe("draftFromVersion", () => {
  it("asks for a draft under the given label and reports where it landed", async () => {
    const fetchUtil = acceptingFetch(() => "/Workflows/review/3-0");

    const created = await draftFromVersion(fetchUtil, "/Workflows/review/2-0", "3.0");

    expect(fetchUtil).toHaveBeenCalledWith("/Workflows/review/2-0.draft.json",
      expect.objectContaining({ method: "POST" }));
    expect(paramsOf(fetchUtil, 0)).toEqual({ version: "3.0" });
    expect(created).toBe("/Workflows/review/3-0");
  });

  it("complains rather than guessing when the engine does not say where the draft is", async () => {
    await expect(draftFromVersion(completingFetch(), "/Workflows/review/2-0", "3.0"))
      .rejects.toThrow("did not say where");
  });

  it("reports the refusal in the words the engine used", async () => {
    const fetchUtil = refusingFetch(409, "This workflow already has a version 3.0");

    await expect(draftFromVersion(fetchUtil, "/Workflows/review/2-0", "3.0"))
      .rejects.toThrow("This workflow already has a version 3.0");
  });
});
