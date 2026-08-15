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

import { loadWorkflowList, parseWorkflowList } from "@iap/workflows/workflowModel";

const listing = {
  "jcr:primaryType": "wf:WorkflowsHomepage",
  "jcr:createdBy": "admin",
  "review": {
    "jcr:primaryType": "wf:WorkflowDefinition",
    "title": "Standard review",
    "1-0": {
      "jcr:primaryType": "wf:WorkflowVersion",
      "version": "1.0",
      "description": "The initial cut",
    },
    "2-0": {
      "jcr:primaryType": "wf:WorkflowVersion",
      "version": "2.0",
    },
    "notAVersion": {
      "jcr:primaryType": "nt:unstructured",
    },
  },
  "untitled": {
    "jcr:primaryType": "wf:WorkflowDefinition",
    "1-0": {
      "jcr:primaryType": "wf:WorkflowVersion",
      "version": "1.0",
    },
  },
  "dangling": null,
};

describe("parseWorkflowList", () => {
  it("flattens definitions into one summary per version", () => {
    const versions = parseWorkflowList(listing);

    expect(versions.map(v => v.name)).toEqual(["review/1-0", "review/2-0", "untitled/1-0"]);
    expect(versions[0]).toEqual({
      name: "review/1-0",
      path: "/Workflows/review/1-0",
      title: "Standard review",
      version: "1.0",
      description: "The initial cut",
    });
  });

  it("defaults missing fields, falling back to the node name for a missing title", () => {
    const versions = parseWorkflowList(listing);

    expect(versions[1]).toEqual(expect.objectContaining({ description: "" }));
    expect(versions[2].title).toBe("untitled");
  });

  it("ignores non-definition entries, non-version children, and dangling nulls", () => {
    // The fixture holds plain properties, a foreign-typed child, and a null entry; none of them
    // may leak into the summaries (or crash the parsing).
    expect(parseWorkflowList(listing)).toHaveLength(3);
    expect(parseWorkflowList({ "jcr:primaryType": "wf:WorkflowsHomepage" })).toEqual([]);
  });
});

describe("loadWorkflowList", () => {
  it("asks for the two levels a listing renders, and summarizes what comes back", async () => {
    const fetchUtil = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(listing),
    });

    expect((await loadWorkflowList(fetchUtil)).map(v => v.name))
      .toEqual(["review/1-0", "review/2-0", "untitled/1-0"]);
    expect(fetchUtil).toHaveBeenCalledWith("/Workflows.2.json");
  });

  // A refusal answers with an error page rather than with JSON, so the status has to be read before
  // the body: parsing first reports how the body disappointed the parser, not what was refused.
  it("rejects with the status when the server refused, without reading the body", async () => {
    const json = vi.fn().mockRejectedValue(new SyntaxError("Unexpected token '<'"));
    const fetchUtil = vi.fn().mockResolvedValue({ ok: false, status: 403, json });

    await expect(loadWorkflowList(fetchUtil)).rejects.toMatchObject({ status: 403 });
    expect(json).not.toHaveBeenCalled();
  });
});
