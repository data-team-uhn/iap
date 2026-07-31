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

import { render, screen } from "@testing-library/react";

import WorkflowsWidget from "@iap/workflows/WorkflowsWidget";

const listing = {
  "jcr:primaryType": "wf:WorkflowsHomepage",
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
  },
};

const stubFetch = (body: unknown) =>
  vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({
    ok: true, status: 200, statusText: "OK",
    json: () => Promise.resolve(body),
  } as unknown as Response)));

afterEach(() => vi.unstubAllGlobals());

describe("WorkflowsWidget", () => {
  it("lists one line per workflow version, with the description as detail", async () => {
    stubFetch(listing);
    render(<WorkflowsWidget />);

    expect(await screen.findByText("Standard review (v1.0)")).toBeInTheDocument();
    expect(screen.getByText("Standard review (v2.0)")).toBeInTheDocument();
    expect(screen.getByText("The initial cut")).toBeInTheDocument();
  });

  it("reports a loading failure without crashing the console", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({
      ok: false, status: 500, statusText: "Server Error",
    } as unknown as Response)));
    render(<WorkflowsWidget />);

    expect(await screen.findByText("The workflows could not be loaded.")).toBeInTheDocument();
  });

  it("reports an empty workflow list", async () => {
    stubFetch({ "jcr:primaryType": "wf:WorkflowsHomepage" });
    render(<WorkflowsWidget />);

    expect(await screen.findByText("No workflows are defined yet.")).toBeInTheDocument();
  });
});
