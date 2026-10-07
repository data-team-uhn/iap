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
  consoleTarget,
  homepagesFrom,
  workflowFrom,
  type WorkflowHomepage,
} from "@iap/workflows/workflowModel";

const definition = {
  "jcr:primaryType": "wf:WorkflowDefinition",
  "title": "Standard review",
  "jcr:created": "2026-07-01T09:00:00.000Z",
  "jcr:lastModified": "2026-08-02T11:30:00.000Z",
  "@events": [ "createVersion", "save" ],
  "1-0": {
    "jcr:primaryType": "wf:WorkflowVersion",
    "version": "1.0",
    "description": "The initial cut",
    "tags": ["retired"],
    "jcr:lastModified": "2026-07-15T09:00:00.000Z",
    "@events": [ "activate" ],
  },
  "2-0": {
    "jcr:primaryType": "wf:WorkflowVersion",
    "version": "2.0",
    "tags": ["active"],
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
describe("workflowFrom", () => {
  it("summarizes the definition and its versions", () => {
    const workflow = workflowFrom("/Workflows/review", definition);

    expect(workflow).toMatchObject({
      path: "/Workflows/review",
      name: "review",
      title: "Standard review",
      active: true,
      "@events": [ "createVersion", "save" ],
    });
    expect(workflow.versions.map(version => version.version)).toEqual(["1.0", "2.0", "3.0"]);
    expect(workflow.versions[0]).toEqual({
      name: "1-0",
      path: "/Workflows/review/1-0",
      version: "1.0",
      description: "The initial cut",
      tags: ["retired"],
      lastModified: "2026-07-15T09:00:00.000Z",
      "@events": [ "activate" ],
    });
  });

  it("reads a workflow as running exactly while one of its versions is active", () => {
    // Not a property of the definition: the same question asked of the versions, so the two can never
    // disagree. A trial doesn't count, since instances are never created from one.
    const running = workflowFrom("/Workflows/review", definition);
    expect(running.active).toBe(true);

    const notRunning = workflowFrom("/Workflows/review", {
      "jcr:primaryType": "wf:WorkflowDefinition",
      "title": "Standard review",
      "1-0": { "jcr:primaryType": "wf:WorkflowVersion", "version": "1.0", "tags": ["trial"] },
      "2-0": { "jcr:primaryType": "wf:WorkflowVersion", "version": "2.0", "tags": ["retired"] },
    });
    expect(notRunning.active).toBe(false);
  });

  it("reads a workflow as retired when a version is retired and none is active", () => {
    const running = workflowFrom("/Workflows/review", definition);
    expect(running.retired).toBe(false);

    const retired = workflowFrom("/Workflows/review", {
      "jcr:primaryType": "wf:WorkflowDefinition",
      "1-0": { "jcr:primaryType": "wf:WorkflowVersion", "version": "1.0", "tags": ["retired"] },
      "2-0": { "jcr:primaryType": "wf:WorkflowVersion", "version": "2.0", "tags": ["trial"] },
    });
    expect(retired.retired).toBe(true);

    // Never having run is not the same as having been retired
    const unreleased = workflowFrom("/Workflows/review", {
      "jcr:primaryType": "wf:WorkflowDefinition",
      "1-0": { "jcr:primaryType": "wf:WorkflowVersion", "version": "1.0", "tags": ["draft"] },
    });
    expect(unreleased.retired).toBe(false);
  });

  it("ignores children that are not versions, and dangling nulls", () => {
    // typeof null === "object", so the null entry is exactly the kind of thing a listing must not
    // trip over
    const workflow = workflowFrom("/Workflows/review", definition);

    expect(workflow.versions).toHaveLength(3);
  });

  it("reads a version with no tags as having none, and fills in what is missing", () => {
    const workflow = workflowFrom("/Workflows/review", definition);

    expect(workflow.versions[2]).toMatchObject({
      version: "3.0", tags: [], description: "", lastModified: "", "@events": [],
    });
  });

  it("falls back to the node name for an untitled workflow", () => {
    const workflow = workflowFrom("/Workflows/review", { "jcr:primaryType": "wf:WorkflowDefinition" });

    expect(workflow.title).toBe("review");
    expect(workflow.active).toBe(false);
    expect(workflow.versions).toEqual([]);
  });
});

describe("homepagesFrom", () => {
  it("reads the homepages a discovery answer names, in its order", () => {
    expect(homepagesFrom({ homepages: [
      { path: "/Workflows", title: "Workflows" },
      { path: "/SystemWorkflows", title: "System workflows" },
    ] })).toEqual([
      { path: "/Workflows", title: "Workflows" },
      { path: "/SystemWorkflows", title: "System workflows" },
    ]);
  });

  it("skips entries with nothing usable in them, and an answer holding no list", () => {
    expect(homepagesFrom({ homepages: [ { title: "Nowhere" }, "not a homepage", null ] })).toEqual([]);
    expect(homepagesFrom({ homepages: "/Workflows" })).toEqual([]);
  });
});

describe("the console's URLs", () => {
  // What this instance has, as the console discovers it: two homepages, one nested inside the other.
  // That's the case that decides how the homepage is found.
  const homepage = (path: string): WorkflowHomepage => ({ path, title: `The ${path} homepage` });
  const HOMEPAGES = [ "/Workflows", "/SystemWorkflows", "/Content/Workflows" ].map(homepage);
  const [ WORKFLOWS, , CONTENT ] = HOMEPAGES;

  it("carries the repository path, and names only the page that needs naming", () => {
    expect(adminUrl("/Workflows/review")).toBe("/admin/workflows/Workflows/review");
    expect(adminUrl("/SystemWorkflows/newEntity/1-0")).toBe("/admin/workflows/SystemWorkflows/newEntity/1-0");
    // The page is asked for by a suffix, so the path stays the thing being looked at: the editor is
    // the version's own URL asked a second way, not a URL below it
    expect(adminUrl("/Workflows/review/2-0", "edit")).toBe("/admin/workflows/Workflows/review/2-0.edit");
  });

  it("reads the editor's suffix as a mode of the version it is put on", () => {
    expect(consoleTarget("/admin/workflows/Workflows/review/2-0", HOMEPAGES))
      .toEqual({ kind: "version", path: "/Workflows/review/2-0", homepage: WORKFLOWS, editing: false });
    expect(consoleTarget("/admin/workflows/Workflows/review/2-0.edit", HOMEPAGES))
      .toEqual({ kind: "version", path: "/Workflows/review/2-0", homepage: WORKFLOWS, editing: true });
    expect(consoleTarget("/admin/workflows/Content/Workflows/review/1-0.edit", HOMEPAGES))
      .toEqual({ kind: "version", path: "/Content/Workflows/review/1-0", homepage: CONTENT, editing: true });
  });

  it("names nothing when the editor is asked for on something that has none", () => {
    // Only a version is edited here. A suffix elsewhere is a URL the console never produces, so it
    // is said to name nothing rather than being dropped to show the page it was put on
    expect(consoleTarget("/admin/workflows/Workflows/review.edit", HOMEPAGES)).toEqual({ kind: "unknown" });
    expect(consoleTarget("/admin/workflows/Workflows.edit", HOMEPAGES)).toEqual({ kind: "unknown" });
    expect(consoleTarget("/admin/workflows.edit", HOMEPAGES)).toEqual({ kind: "unknown" });
    expect(consoleTarget("/admin/workflows/Elsewhere/review/1-0.edit", HOMEPAGES)).toEqual({ kind: "unknown" });
  });

  it("reads each depth below a homepage as what it is", () => {
    // Every prefix of a console URL is a page of its own, which is the point of this shape: dropping
    // a segment moves up to the thing that contains what was being looked at
    expect(consoleTarget("/admin/workflows/Workflows", HOMEPAGES))
      .toEqual({ kind: "homepage", path: "/Workflows" });
    expect(consoleTarget("/admin/workflows/Workflows/review", HOMEPAGES))
      .toEqual({ kind: "workflow", path: "/Workflows/review", homepage: WORKFLOWS });
    expect(consoleTarget("/admin/workflows/Workflows/review/2-0", HOMEPAGES))
      .toEqual({ kind: "version", path: "/Workflows/review/2-0", homepage: WORKFLOWS, editing: false });
  });

  it("treats the trailing slash and the .html a bookmark may carry as the same page", () => {
    expect(consoleTarget("/admin/workflows/Workflows/review/", HOMEPAGES))
      .toEqual({ kind: "workflow", path: "/Workflows/review", homepage: WORKFLOWS });
    expect(consoleTarget("/admin/workflows/Workflows/review.html", HOMEPAGES))
      .toEqual({ kind: "workflow", path: "/Workflows/review", homepage: WORKFLOWS });
  });

  it("counts depth from the homepage, since a homepage may be anywhere", () => {
    // Counting from the root would read this workflow as a version of /Content/Workflows.
    // The homepage is the only fixed point: below one it's always homepage/workflow/version.
    expect(consoleTarget("/admin/workflows/Content/Workflows/review", HOMEPAGES))
      .toEqual({ kind: "workflow", path: "/Content/Workflows/review", homepage: CONTENT });
    expect(consoleTarget("/admin/workflows/Content/Workflows/review/1-0", HOMEPAGES))
      .toEqual({ kind: "version", path: "/Content/Workflows/review/1-0", homepage: CONTENT, editing: false });
    expect(adminUrl("/Content/Workflows/review")).toBe("/admin/workflows/Content/Workflows/review");
  });

  it("takes the longest homepage a URL starts with, so a nested one wins over its container", () => {
    // /Content/Workflows sits inside nothing here, but a homepage stored under another homepage's
    // path would be read as a workflow of it if the shortest match were taken
    expect(consoleTarget("/admin/workflows/Content/Workflows", HOMEPAGES))
      .toEqual({ kind: "homepage", path: "/Content/Workflows" });
    expect(consoleTarget("/admin/workflows/Content/Workflows", [ "/Content", "/Content/Workflows" ].map(homepage)))
      .toEqual({ kind: "homepage", path: "/Content/Workflows" });
    // Whichever order they were discovered in
    expect(consoleTarget("/admin/workflows/Content/Workflows", [ "/Content/Workflows", "/Content" ].map(homepage)))
      .toEqual({ kind: "homepage", path: "/Content/Workflows" });
  });

  it("reads a version named after a page as itself", () => {
    // Nothing in a path is taken for a page — a page is asked for in the query — so no name below a
    // homepage is reserved
    expect(consoleTarget("/admin/workflows/Workflows/review/edit", HOMEPAGES))
      .toEqual({ kind: "version", path: "/Workflows/review/edit", homepage: WORKFLOWS, editing: false });
    expect(consoleTarget("/admin/workflows/Workflows/edit/edit", HOMEPAGES))
      .toEqual({ kind: "version", path: "/Workflows/edit/edit", homepage: WORKFLOWS, editing: false });
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
  });

  it("reads the console's own root as the way in rather than as a page", () => {
    // A listing belongs to a homepage, so the root addresses nothing — which is decided without
    // knowing any homepage, the one URL here a caller may resolve before discovery lands
    expect(consoleTarget("/admin/workflows", HOMEPAGES)).toEqual({ kind: "root" });
    expect(consoleTarget("/admin/workflows", [])).toEqual({ kind: "root" });
    expect(consoleTarget("/admin/workflows/", HOMEPAGES)).toEqual({ kind: "root" });
    expect(consoleTarget("/admin/workflows.html", HOMEPAGES)).toEqual({ kind: "root" });
  });
});
