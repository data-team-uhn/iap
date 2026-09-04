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

import {
  adminUrl,
  consolePage,
  consoleTarget,
  forgetWorkflowHomepages,
  loadWorkflow,
  loadWorkflowCounts,
  loadWorkflowHomepages,
  stateOf,
} from "@iap/workflows/workflowModel";

const definition = {
  "jcr:primaryType": "wf:WorkflowDefinition",
  "title": "Standard review",
  "jcr:created": "2026-07-01T09:00:00.000Z",
  "jcr:lastModified": "2026-08-02T11:30:00.000Z",
  "1-0": {
    "jcr:primaryType": "wf:WorkflowVersion",
    "version": "1.0",
    "description": "The initial cut",
    "state": "RETIRED",
    "jcr:lastModified": "2026-07-15T09:00:00.000Z",
  },
  "2-0": {
    "jcr:primaryType": "wf:WorkflowVersion",
    "version": "2.0",
    "state": "ACTIVE",
  },
  "3-0": {
    "jcr:primaryType": "wf:WorkflowVersion",
    "version": "3.0",
  },
  "notAVersion": {
    "jcr:primaryType": "nt:unstructured",
  },
  "dangling": null,
};

// A stubbed fetch, and a server that answers with the given body.
type FetchStub = (url: string) => Promise<Response>;

const okResponse = (body: unknown) =>
  ({ ok: true, status: 200, json: () => Promise.resolve(body) } as unknown as Response);

const answering = (body: unknown) => vi.fn<FetchStub>(() => Promise.resolve(okResponse(body)));

describe("stateOf", () => {
  it("reads each of the four lifecycle states", () => {
    expect(stateOf("DRAFT")).toBe("DRAFT");
    expect(stateOf("TRIAL")).toBe("TRIAL");
    expect(stateOf("ACTIVE")).toBe("ACTIVE");
    expect(stateOf("RETIRED")).toBe("RETIRED");
  });

  it("reads anything else as a draft", () => {
    // A version with no state stored, or one from a platform that knows a state this one does not:
    // both read as the state nothing is ever instantiated from, never as the one that runs
    expect(stateOf(undefined)).toBe("DRAFT");
    expect(stateOf("active")).toBe("DRAFT");
    expect(stateOf("PUBLISHED")).toBe("DRAFT");
    expect(stateOf(3)).toBe("DRAFT");
  });
});

describe("loadWorkflow", () => {
  it("asks for the two levels the page renders, and summarizes what comes back", async () => {
    const fetchUtil = answering(definition);

    const workflow = await loadWorkflow(fetchUtil, "/Workflows/review");

    expect(fetchUtil).toHaveBeenCalledWith("/Workflows/review.2.json");
    expect(workflow).toMatchObject({
      path: "/Workflows/review",
      name: "review",
      title: "Standard review",
      active: true,
    });
    expect(workflow.versions.map(version => version.version)).toEqual(["1.0", "2.0", "3.0"]);
    expect(workflow.versions[0]).toEqual({
      name: "1-0",
      path: "/Workflows/review/1-0",
      version: "1.0",
      description: "The initial cut",
      state: "RETIRED",
      lastModified: "2026-07-15T09:00:00.000Z",
    });
  });

  it("reads a workflow as running exactly while one of its versions is active", async () => {
    // Not a property of the definition: the same question asked of the versions, so the two can never
    // disagree. A trial doesn't count, since instances are never created from one.
    const running = await loadWorkflow(answering(definition), "/Workflows/review");
    expect(running.active).toBe(true);

    const notRunning = await loadWorkflow(answering({
      "jcr:primaryType": "wf:WorkflowDefinition",
      "title": "Standard review",
      "1-0": { "jcr:primaryType": "wf:WorkflowVersion", "version": "1.0", "state": "TRIAL" },
      "2-0": { "jcr:primaryType": "wf:WorkflowVersion", "version": "2.0", "state": "RETIRED" },
    }), "/Workflows/review");
    expect(notRunning.active).toBe(false);
  });

  it("ignores children that are not versions, and dangling nulls", async () => {
    // typeof null === "object", so the null entry is exactly the kind of thing a listing must not
    // trip over
    const workflow = await loadWorkflow(answering(definition), "/Workflows/review");

    expect(workflow.versions).toHaveLength(3);
  });

  it("reads a version with no state as a draft, and fills in what is missing", async () => {
    const fetchUtil = answering(definition);

    const workflow = await loadWorkflow(fetchUtil, "/Workflows/review");

    expect(workflow.versions[2]).toMatchObject({ version: "3.0", state: "DRAFT", description: "", lastModified: "" });
  });

  it("falls back to the node name for an untitled workflow", async () => {
    const fetchUtil = answering({ "jcr:primaryType": "wf:WorkflowDefinition" });

    const workflow = await loadWorkflow(fetchUtil, "/Workflows/review");

    expect(workflow.title).toBe("review");
    expect(workflow.active).toBe(false);
    expect(workflow.versions).toEqual([]);
  });

  // A refusal answers with an error page rather than with JSON, so the status has to be read before
  // the body: parsing first reports how the body disappointed the parser, not what was refused.
  it("rejects with the status when the server refused, without reading the body", async () => {
    const json = vi.fn().mockRejectedValue(new SyntaxError("Unexpected token '<'"));
    const fetchUtil = vi.fn<FetchStub>(() => Promise.resolve({ ok: false, status: 403, json } as unknown as Response));

    await expect(loadWorkflow(fetchUtil, "/Workflows/review")).rejects.toMatchObject({ status: 403 });
    expect(json).not.toHaveBeenCalled();
  });
});

describe("loadWorkflowHomepages", () => {
  // The discovery is kept for the life of the session, so each test starts from an unasked one
  beforeEach(forgetWorkflowHomepages);

  it("keeps what it discovered, since every console URL is read against it", async () => {
    // Asked on every navigation rather than once per page, and answered by a tree that changes only
    // when a bundle is installed: asking again on each would be a request per click for nothing
    const fetchUtil = answering({ homepages: [ { path: "/Workflows", title: "Workflows" } ] });

    await loadWorkflowHomepages(fetchUtil);
    await loadWorkflowHomepages(fetchUtil);

    expect(fetchUtil).toHaveBeenCalledTimes(1);
  });

  it("shares one request between callers that ask before it lands", async () => {
    const fetchUtil = answering({ homepages: [ { path: "/Workflows", title: "Workflows" } ] });

    await Promise.all([ loadWorkflowHomepages(fetchUtil), loadWorkflowHomepages(fetchUtil) ]);

    expect(fetchUtil).toHaveBeenCalledTimes(1);
  });

  it("asks the canonical homepage which homepages hold workflows", async () => {
    const fetchUtil = answering({
      homepages: [
        { path: "/Workflows", title: "Workflows" },
        { path: "/SystemWorkflows", title: "System workflows" },
      ],
    });

    const homepages = await loadWorkflowHomepages(fetchUtil);

    expect(fetchUtil).toHaveBeenCalledWith("/Workflows.homepages.json");
    expect(homepages).toEqual([
      { path: "/Workflows", title: "Workflows" },
      { path: "/SystemWorkflows", title: "System workflows" },
    ]);
  });

  it("skips entries with nothing usable in them", async () => {
    const fetchUtil = answering({
      homepages: [ { title: "Nowhere" }, "not a homepage", null, { path: "/Workflows", title: "Workflows" } ],
    });

    await expect(loadWorkflowHomepages(fetchUtil)).resolves.toEqual([ { path: "/Workflows", title: "Workflows" } ]);
  });

  it("falls back to the homepage everybody has when the discovery cannot be made", async () => {
    // A deployment whose endpoint is missing, or a refusal: listing the workflows everyone can see
    // beats listing none at all
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    const fetchUtil = vi.fn<FetchStub>(() =>
      Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve({}) } as unknown as Response));

    await expect(loadWorkflowHomepages(fetchUtil)).resolves.toEqual([ { path: "/Workflows", title: "Workflows" } ]);

    vi.mocked(console.error).mockRestore();
  });

  it("answers with no homepages when the server says there are none", async () => {
    const fetchUtil = answering({ homepages: [] });

    await expect(loadWorkflowHomepages(fetchUtil)).resolves.toEqual([]);
  });

  it("answers with no homepages when the answer holds no list of them", async () => {
    // An answer in a shape this reader does not know is an answer it can make nothing of, which is
    // not the same as an unreachable endpoint: there is nothing to fall back to
    await expect(loadWorkflowHomepages(answering({}))).resolves.toEqual([]);
    await expect(loadWorkflowHomepages(answering({ homepages: "/Workflows" }))).resolves.toEqual([]);
  });
});

describe("loadWorkflowCounts", () => {
  // It counts through the discovery, which is kept for the session
  beforeEach(forgetWorkflowHomepages);

  // The discovery endpoint, then one count per homepage it named.
  const server = (homepages: unknown[], counts: Record<string, unknown>) => vi.fn<FetchStub>(url =>
    Promise.resolve(okResponse(url === "/Workflows.homepages.json"
      ? { homepages }
      : counts[url.replace(".paginate.json?offset=0&limit=0", "")])));

  const page = (total: number, approximate = false) => ({
    rows: [], offset: 0, limit: 0, returnedrows: 0, totalrows: total, totalIsApproximate: approximate,
  });

  it("counts the workflows of each homepage without listing any of them", async () => {
    const fetchUtil = server(
      [ { path: "/Workflows", title: "Workflows" }, { path: "/SystemWorkflows", title: "System workflows" } ],
      { "/Workflows": page(3), "/SystemWorkflows": page(12) }
    );

    const counts = await loadWorkflowCounts(fetchUtil);

    expect(counts).toEqual([
      { path: "/Workflows", title: "Workflows", count: 3, atLeast: false },
      { path: "/SystemWorkflows", title: "System workflows", count: 12, atLeast: false },
    ]);
    // A page of no rows at all: the count is the whole answer
    expect(fetchUtil).toHaveBeenCalledWith("/Workflows.paginate.json?offset=0&limit=0");
    expect(fetchUtil).toHaveBeenCalledWith("/SystemWorkflows.paginate.json?offset=0&limit=0");
  });

  it("passes on that a count the server stopped short of finishing is a lower bound", async () => {
    const fetchUtil = server([ { path: "/Workflows", title: "Workflows" } ], { "/Workflows": page(100, true) });

    await expect(loadWorkflowCounts(fetchUtil)).resolves.toEqual([
      { path: "/Workflows", title: "Workflows", count: 100, atLeast: true },
    ]);
  });

  it("has nothing to count when there is nowhere workflows are stored", async () => {
    await expect(loadWorkflowCounts(answering({ homepages: [] }))).resolves.toEqual([]);
  });

  it("still names a homepage whose count was refused, without a number for it", async () => {
    // The homepages are independent, so one that can't be counted loses only its own number.
    // That it exists is still worth reporting, and is known either way.
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    const fetchUtil = vi.fn<FetchStub>(url => {
      if (url === "/Workflows.homepages.json") {
        return Promise.resolve(okResponse({ homepages: [
          { path: "/Workflows", title: "Workflows" },
          { path: "/SystemWorkflows", title: "System workflows" },
        ] }));
      }
      return url.startsWith("/SystemWorkflows")
        ? Promise.resolve({ ok: false, status: 503, json: () => Promise.resolve({}) } as unknown as Response)
        : Promise.resolve(okResponse(page(3)));
    });

    await expect(loadWorkflowCounts(fetchUtil)).resolves.toEqual([
      { path: "/Workflows", title: "Workflows", count: 3, atLeast: false },
      { path: "/SystemWorkflows", title: "System workflows", atLeast: false },
    ]);

    vi.mocked(console.error).mockRestore();
  });
});

describe("the console's URLs", () => {
  // What this instance has, as the console discovers it: two homepages, one nested inside the other.
  // That's the case that decides how the homepage is found.
  const HOMEPAGES = [ "/Workflows", "/SystemWorkflows", "/Content/Workflows" ];

  it("carries the repository path, and names only the page that needs naming", () => {
    expect(adminUrl("/Workflows/review")).toBe("/admin/workflows/Workflows/review");
    expect(adminUrl("/SystemWorkflows/newEntity/1-0")).toBe("/admin/workflows/SystemWorkflows/newEntity/1-0");
    // The page is asked for in the query, so the path stays the thing being looked at: the editor is
    // the version's own URL asked a second way, not a URL below it
    expect(adminUrl("/Workflows/review/2-0", "edit")).toBe("/admin/workflows/Workflows/review/2-0?page=edit");
  });

  it("reads the page a URL's query asks for, and nothing else as one", () => {
    expect(consolePage("?page=edit")).toBe("edit");
    expect(consolePage("")).toBeUndefined();
    expect(consolePage("?page=rename")).toBeUndefined();
    // Whatever else a URL carries is none of this question's business
    expect(consolePage("?tab=2&page=edit")).toBe("edit");
  });

  it("reads each depth below a homepage as what it is", () => {
    // Every prefix of a console URL is a page of its own, which is the point of this shape: dropping
    // a segment moves up to the thing that contains what was being looked at
    expect(consoleTarget("/admin/workflows/Workflows", HOMEPAGES))
      .toEqual({ kind: "homepage", path: "/Workflows" });
    expect(consoleTarget("/admin/workflows/Workflows/review", HOMEPAGES))
      .toEqual({ kind: "workflow", path: "/Workflows/review" });
    expect(consoleTarget("/admin/workflows/Workflows/review/2-0", HOMEPAGES))
      .toEqual({ kind: "version", path: "/Workflows/review/2-0" });
  });

  it("treats the trailing slash and the .html a bookmark may carry as the same page", () => {
    expect(consoleTarget("/admin/workflows/Workflows/review/", HOMEPAGES))
      .toEqual({ kind: "workflow", path: "/Workflows/review" });
    expect(consoleTarget("/admin/workflows/Workflows/review.html", HOMEPAGES))
      .toEqual({ kind: "workflow", path: "/Workflows/review" });
  });

  it("counts depth from the homepage, since a homepage may be anywhere", () => {
    // Counting from the root would read this workflow as a version of /Content/Workflows.
    // The homepage is the only fixed point: below one it's always homepage/workflow/version.
    expect(consoleTarget("/admin/workflows/Content/Workflows/review", HOMEPAGES))
      .toEqual({ kind: "workflow", path: "/Content/Workflows/review" });
    expect(consoleTarget("/admin/workflows/Content/Workflows/review/1-0", HOMEPAGES))
      .toEqual({ kind: "version", path: "/Content/Workflows/review/1-0" });
    expect(adminUrl("/Content/Workflows/review")).toBe("/admin/workflows/Content/Workflows/review");
  });

  it("takes the longest homepage a URL starts with, so a nested one wins over its container", () => {
    // /Content/Workflows sits inside nothing here, but a homepage stored under another homepage's
    // path would be read as a workflow of it if the shortest match were taken
    expect(consoleTarget("/admin/workflows/Content/Workflows", HOMEPAGES))
      .toEqual({ kind: "homepage", path: "/Content/Workflows" });
    expect(consoleTarget("/admin/workflows/Content/Workflows", [ "/Content", "/Content/Workflows" ]))
      .toEqual({ kind: "homepage", path: "/Content/Workflows" });
  });

  it("reads a version named after a page as itself", () => {
    // Nothing in a path is taken for a page — a page is asked for in the query — so no name below a
    // homepage is reserved
    expect(consoleTarget("/admin/workflows/Workflows/review/edit", HOMEPAGES))
      .toEqual({ kind: "version", path: "/Workflows/review/edit" });
    expect(consoleTarget("/admin/workflows/Workflows/edit/edit", HOMEPAGES))
      .toEqual({ kind: "version", path: "/Workflows/edit/edit" });
  });

  it("knows nothing about a URL it cannot place", () => {
    // A tree that isn't a homepage here, and more segments than a version can account for.
    // Rendering an empty workflow for either would be worse than saying so.
    expect(consoleTarget("/admin/workflows/Elsewhere/review", HOMEPAGES)).toEqual({ kind: "unknown" });
    expect(consoleTarget("/admin/workflows/Workflows/review/2-0/rename", HOMEPAGES))
      .toEqual({ kind: "unknown" });
    expect(consoleTarget("/admin/workflows/Workflows/review/2-0/edit", HOMEPAGES))
      .toEqual({ kind: "unknown" });
    expect(consoleTarget("/admin/categories", HOMEPAGES)).toEqual({ kind: "unknown" });
    expect(consoleTarget("/", HOMEPAGES)).toEqual({ kind: "unknown" });
    // The console's own root, which is not routed here at all: a listing belongs to a homepage, and
    // a root that showed whichever came first was a second URL, and a second crumb, for that page
    expect(consoleTarget("/admin/workflows", HOMEPAGES)).toEqual({ kind: "unknown" });
  });
});
