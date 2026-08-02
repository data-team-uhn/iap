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

import { act, render, screen } from "@testing-library/react";

import { getEntityTypeConfig } from "@iap/frontend-commons/entityGrid/registry";
import { SUBMISSION_TYPE, schemaLabel } from "@iap/submissions/submissionGrid";
import { clearTagDefinitionsCache } from "@iap/tags/tagDefinitions";
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
    // An unexpanded reference is serialized as a plain UUID string
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
    // Ordering by a multivalued property has no meaningful semantics
    expect(status?.sortable).toBe(false);

    // The filterable choices come from the lifecycle tag definitions: the first call triggers
    // the fetch, and once it resolves the defined names are offered with their labels
    const options = status && "valueOptions" in status && typeof status.valueOptions === "function"
      ? status.valueOptions
      : undefined;
    expect(options?.({ field: "tags" })).toEqual([]);
    await vi.waitFor(() => {
      // The colors ride along, surfacing as colored chips in the "is any of" filter input
      expect(options?.({ field: "tags" })).toEqual([
        { value: "draft", label: "Draft", color: "#9e9e9e" },
        { value: "submitted", label: "Submitted", color: "#1976d2" },
        { value: "in-review", label: "In review", color: "#673ab7" },
        { value: "changes-requested", label: "Changes requested", color: "#ed6c02" },
        { value: "approved", label: "Approved", color: "#2e7d32" },
        { value: "rejected", label: "Rejected", color: "#d32f2f" },
      ]);
    });

    vi.unstubAllGlobals();
    clearTagDefinitionsCache();
  });

  it("links each row to the submission's own page", () => {
    expect(config?.rowLink?.({ "@path": "/Submissions/demo-1" })).toBe("/Submissions/demo-1");
    // A row somehow missing its path is not clickable rather than a broken link
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

  const ALL_CARD_FIELDS = new Set(["title", "schemaVersion", "tags", "jcr:created", "jcr:lastModified"]);

  it("renders a compact card for the grid's narrow-screen list mode", async () => {
    vi.stubGlobal("fetch", vi.fn(tagAwareFetch({})));

    render(<>{config?.listItem?.({
      "@path": "/Submissions/s1",
      "title": "Test my drug",
      "tags": ["in-review"],
      "schemaVersion": { "@path": "/Schemas/ClinicalTrial/1.0", "version": "1.0" },
      "jcr:lastModified": "2026-07-02T10:00:00.000-04:00",
    }, ALL_CARD_FIELDS)}</>);

    expect(screen.getByText("Test my drug")).toBeInTheDocument();
    expect(await screen.findByText("In review")).toBeInTheDocument();
    expect(screen.getByText(/ClinicalTrial 1.0 •/)).toBeInTheDocument();

    vi.unstubAllGlobals();
    clearTagDefinitionsCache();
  });

  it("leaves hidden columns out of the card, except the identifying title", async () => {
    vi.stubGlobal("fetch", vi.fn(tagAwareFetch({})));

    const { container } = render(<>{config?.listItem?.({
      "@path": "/Submissions/s1",
      "title": "Test my drug",
      "tags": ["in-review"],
      "schemaVersion": { "@path": "/Schemas/ClinicalTrial/1.0", "version": "1.0" },
      "jcr:lastModified": "2026-07-02T10:00:00.000-04:00",
    }, new Set(["jcr:lastModified"]))}</>);

    // The title stays — it is the card's identity — and the visible date keeps its row,
    // no longer joined to the hidden schema label
    expect(screen.getByText("Test my drug")).toBeInTheDocument();
    expect(screen.getByText(new Date("2026-07-02T10:00:00.000-04:00").toLocaleDateString())).toBeInTheDocument();
    expect(screen.queryByText(/ClinicalTrial/)).toBeNull();
    // The status chip is gone: the tag definitions are never even fetched
    await act(() => Promise.resolve());
    expect(container.textContent).not.toContain("In review");

    vi.unstubAllGlobals();
    clearTagDefinitionsCache();
  });

  it("falls back to the node name in the card when the title is missing", () => {
    vi.stubGlobal("fetch", vi.fn(tagAwareFetch({})));

    render(<>{config?.listItem?.({ "@path": "/Submissions/bare", "@name": "bare" }, ALL_CARD_FIELDS)}</>);

    expect(screen.getByText("bare")).toBeInTheDocument();

    vi.unstubAllGlobals();
    clearTagDefinitionsCache();
  });
});
