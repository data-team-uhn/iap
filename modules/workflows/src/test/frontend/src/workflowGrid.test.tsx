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

import { getEntityTypeConfig, type EntityGridColumn } from "@iap/frontend-commons/entityGrid/registry";
import { WORKFLOW_TYPE } from "@iap/workflows/workflowGrid";

// Importing the module registered the configuration as a side effect
const config = getEntityTypeConfig(WORKFLOW_TYPE);

const columnOf = (field: string): EntityGridColumn => {
  const column = config?.columns.find(candidate => candidate.field === field);
  expect(column).toBeDefined();
  return column!;
};

describe("the registered workflow grid configuration", () => {
  it("lists workflows from the homepage every deployment has, by title", () => {
    // Any other homepage is discovered at runtime and handed to the grid; nothing here needs to know
    // which ones exist
    expect(config?.homepage).toBe("/Workflows");
    expect(config?.defaultSort).toEqual({ field: "title", sort: "asc" });
  });

  it("opens the page that manages a workflow when its row is clicked", () => {
    expect(config?.rowLink?.({ "@path": "/Workflows/review" })).toBe("/admin/workflows/Workflows/review");
    expect(config?.rowLink?.({ "@path": "/SystemWorkflows/newEntity" }))
      .toBe("/admin/workflows/SystemWorkflows/newEntity");
  });

  it("leaves a row with no path of its own unclickable", () => {
    // A projection that omitted @path leaves nowhere to go, which is not the same as going nowhere
    expect(config?.rowLink?.({})).toBeUndefined();
  });

  it("does not claim to say whether a workflow runs", () => {
    // Whether a workflow runs is whether one of its versions is active. This listing's definition-node
    // page doesn't carry that; it's answered on the workflow's own page instead.
    expect(config?.columns.map(column => column.field)).not.toContain("active");
  });

  it("reads the repository's timestamps as dates, and anything else as none", () => {
    const created = columnOf("jcr:created").valueGetter as unknown as (value: unknown) => Date | null;

    expect(created("2026-07-01T10:00:00.000-04:00")).toEqual(new Date("2026-07-01T10:00:00.000-04:00"));
    expect(created(1_780_000_000_000)).toEqual(new Date(1_780_000_000_000));
    expect(created(undefined)).toBeNull();
  });

  it("names an untitled workflow by its node name on the narrow-screen card", () => {
    // The card's title is its identity and its tap target, so it cannot be left blank the way a grid
    // cell can
    const title = columnOf("title");
    expect(title.cardValue?.({ title: "Standard review", "@name": "review" })).toBe("Standard review");
    expect(title.cardValue?.({ "@name": "review" })).toBe("review");
  });

  it("dates the card by the day alone, and leaves an undated workflow blank", () => {
    const modified = columnOf("jcr:lastModified");
    expect(modified.cardValue?.({ "jcr:lastModified": "2026-07-02T10:00:00.000-04:00" }))
      .toBe(new Date("2026-07-02T10:00:00.000-04:00").toLocaleDateString());
    expect(modified.cardValue?.({})).toBeUndefined();
  });

  it("keeps the creation timestamp off the card, which the modification day already dates", () => {
    expect(columnOf("jcr:created").cardSlot).toBe("omit");
  });
});
