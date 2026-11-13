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

import { getEntityTypeConfig } from "@iap/frontend-commons/entityGrid/registry";
import { SUBMISSION_TYPE, schemaLabel } from "@iap/submissions/submissionGrid";
import { tagAwareFetch } from "@iap/tags/tagDefinitions.fixture";

// Importing the module registered the configuration as a side effect
const config = getEntityTypeConfig(SUBMISSION_TYPE);

// The column value getters only use the cell value, so they can be probed directly
function valueGetterOf(field: string): (value: unknown) => unknown {
  const getter = config?.columns.find(column => column.field === field)?.valueGetter;
  expect(getter).toBeDefined();
  return getter as unknown as (value: unknown) => unknown;
}

describe("schemaLabel", () => {
  it("combines the schema's name from the version's path with the version label", () => {
    expect(schemaLabel({ "@path": "/Schemas/ClinicalTrial/1.0", "version": "1.0" })).toBe("ClinicalTrial 1.0");
  });

  it("uses whichever of the two parts is available", () => {
    expect(schemaLabel({ "@path": "/Schemas/ClinicalTrial/1.0" })).toBe("ClinicalTrial");
    expect(schemaLabel({ version: "2.0" })).toBe("2.0");
  });

  it("is empty for anything that is not a dereferenced node", () => {
    // A reference that is not dereferenced is serialized as its `jcr:uuid`
    expect(schemaLabel("f8cfa08e-b315-4eed-9d38-af6473fcd48f")).toBe("");
    expect(schemaLabel(undefined)).toBe("");
  });
});

describe("the registered submission grid configuration", () => {
  it("lists submissions from their homepage, latest modified first", () => {
    expect(config?.homepage).toBe("/Submissions");
    expect(config?.defaultSort).toEqual({ field: "jcr:lastModified", sort: "desc" });
  });

  it("presents the lifecycle state through the multivalued tags property", async () => {
    vi.stubGlobal("fetch", vi.fn(tagAwareFetch({})));

    const status = config?.columns.find(column => column.field === "tags");
    expect(status?.headerName).toBe("Status");
    expect(status?.type).toBe("singleSelect");
    expect(status?.sortable).toBe(false);

    // The first call starts reading the lifecycle tag definitions, and offers nothing until they
    // arrive
    const options = status && "valueOptions" in status && typeof status.valueOptions === "function"
      ? status.valueOptions
      : undefined;
    expect(options?.({ field: "tags" })).toEqual([]);
    await vi.waitFor(() => {
      // Each with its label and colour
      expect(options?.({ field: "tags" })).toEqual([
        { value: "draft", label: "Draft", color: "#4c5670" },
        { value: "submitted", label: "Submitted", color: "#1b4f8f" },
        { value: "in-review", label: "In review", color: "#55408f" },
        { value: "changes-requested", label: "Changes requested", color: "#8a5410" },
        { value: "approved", label: "Approved", color: "#1d6a3a" },
        { value: "rejected", label: "Rejected", color: "#8e1b29" },
      ]);
    });

    vi.unstubAllGlobals();
  });

  it("links each row to the submission's own page", () => {
    expect(config?.rowLink?.({ "@path": "/Submissions/demo-1" })).toBe("/Submissions/demo-1");
    // A row without a path links nowhere
    expect(config?.rowLink?.({})).toBeUndefined();
  });

  it("parses date cells into Date objects, tolerating missing values", () => {
    const dates = valueGetterOf("jcr:created");
    expect(dates("2026-07-01T10:00:00.000-04:00")).toEqual(new Date("2026-07-01T10:00:00.000-04:00"));
    expect(dates(undefined)).toBeNull();
  });

  it("renders the schema column through schemaLabel", () => {
    const schema = valueGetterOf("schemaVersion");
    expect(schema({ "@path": "/Schemas/DemoStudy/1.0", "version": "1.0" })).toBe("DemoStudy 1.0");
    expect(schema(undefined)).toBe("");
  });

  it("describes the narrow-screen card through the columns' card slots", () => {
    const slotOf = (field: string) => config?.columns.find(column => column.field === field)?.cardSlot;
    expect(slotOf("title")).toBe("title");
    expect(slotOf("tags")).toBe("badge");
    expect(slotOf("schemaVersion")).toBe("caption");
    expect(slotOf("jcr:lastModified")).toBe("caption");
    expect(slotOf("jcr:created")).toBe("omit");
  });

  it("titles the card with the submission title, falling back to the node name", () => {
    const title = config?.columns.find(column => column.field === "title")?.cardValue;
    expect(title?.({ "title": "Test my drug", "@name": "s1" })).toBe("Test my drug");
    expect(title?.({ "@name": "bare" })).toBe("bare");
  });

  it("compacts the modification timestamp to a day on the card", () => {
    const modified = config?.columns.find(column => column.field === "jcr:lastModified")?.cardValue;
    expect(modified?.({ "jcr:lastModified": "2026-07-02T10:00:00.000-04:00" }))
      .toBe(new Date("2026-07-02T10:00:00.000-04:00").toLocaleDateString());
    expect(modified?.({})).toBeUndefined();
  });
});
