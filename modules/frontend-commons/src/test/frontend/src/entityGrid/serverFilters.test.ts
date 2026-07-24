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

import type { GridFilterItem } from "@mui/x-data-grid-pro";

import type { EntityGridColumn } from "@iap/frontend-commons/entityGrid/registry";
import { toPropertyFilters, withServerFilterOperators } from "@iap/frontend-commons/entityGrid/serverFilters";

const COLUMNS: EntityGridColumn[] = [
  { field: "title", headerName: "Title" },
  { field: "status", headerName: "Status", type: "singleSelect", valueOptions: ["draft", "approved"] },
  { field: "created", headerName: "Created", type: "dateTime", sortProperty: "jcr:created" },
  { field: "amount", headerName: "Amount", type: "number" },
  { field: "schema", headerName: "Schema", filterable: false },
];

function filters(...items: GridFilterItem[]) {
  return toPropertyFilters({ items }, COLUMNS);
}

describe("toPropertyFilters", () => {
  it("maps text operators to case-insensitive wildcard matches and plain comparisons", () => {
    expect(filters(
      { field: "title", operator: "contains", value: "card" },
      { field: "title", operator: "startsWith", value: "Study" },
      { field: "title", operator: "endsWith", value: "imaging" },
      { field: "title", operator: "equals", value: "Study #1" },
      { field: "title", operator: "doesNotEqual", value: "Study #2" },
    )).toEqual([
      { name: "title", comparator: "ILIKE", value: "%card%" },
      { name: "title", comparator: "ILIKE", value: "Study%" },
      { name: "title", comparator: "ILIKE", value: "%imaging" },
      { name: "title", comparator: "=", value: "Study #1" },
      { name: "title", comparator: "<>", value: "Study #2" },
    ]);
  });

  it("maps emptiness operators without requiring a value", () => {
    expect(filters(
      { field: "title", operator: "isEmpty" },
      { field: "title", operator: "isNotEmpty" },
    )).toEqual([
      { name: "title", comparator: "IS NULL", value: "" },
      { name: "title", comparator: "IS NOT NULL", value: "" },
    ]);
  });

  it("maps number operators to range comparators", () => {
    expect(filters(
      { field: "amount", operator: "!=", value: "3" },
      { field: "amount", operator: ">=", value: "5" },
    )).toEqual([
      { name: "amount", comparator: "<>", value: "3" },
      { name: "amount", comparator: ">=", value: "5" },
    ]);
  });

  it("expands date conditions to local day boundaries on the server-side property", () => {
    // Day boundaries in the user's own timezone, as full ISO instants
    const start = new Date("2026-07-01T00:00:00").toISOString();
    const end = new Date("2026-07-02T00:00:00").toISOString();
    expect(filters({ field: "created", operator: "is", value: "2026-07-01" })).toEqual([
      { name: "jcr:created", comparator: ">=", value: start },
      { name: "jcr:created", comparator: "<", value: end },
    ]);
    expect(filters({ field: "created", operator: "after", value: "2026-07-01" }))
      .toEqual([{ name: "jcr:created", comparator: ">=", value: end }]);
    expect(filters({ field: "created", operator: "onOrAfter", value: "2026-07-01" }))
      .toEqual([{ name: "jcr:created", comparator: ">=", value: start }]);
    expect(filters({ field: "created", operator: "before", value: "2026-07-01" }))
      .toEqual([{ name: "jcr:created", comparator: "<", value: start }]);
    expect(filters({ field: "created", operator: "onOrBefore", value: "2026-07-01" }))
      .toEqual([{ name: "jcr:created", comparator: "<", value: end }]);
    // An unparseable day is a condition still being edited
    expect(filters({ field: "created", operator: "is", value: "garbage" })).toEqual([]);
    expect(filters({ field: "created", operator: "is", value: new Date("garbage") })).toEqual([]);
  });

  it("recovers the picked day from the Date object produced by the grid's date input", () => {
    // The grid's date filter input parses the picked YYYY-MM-DD as UTC midnight; the condition
    // must still cover that calendar day in the user's own timezone
    const start = new Date("2026-07-01T00:00:00").toISOString();
    const end = new Date("2026-07-02T00:00:00").toISOString();
    expect(filters({ field: "created", operator: "is", value: new Date("2026-07-01") })).toEqual([
      { name: "jcr:created", comparator: ">=", value: start },
      { name: "jcr:created", comparator: "<", value: end },
    ]);
  });

  it("maps single-select choices to equality comparators", () => {
    expect(filters(
      { field: "status", operator: "is", value: "draft" },
      { field: "status", operator: "not", value: "approved" },
    )).toEqual([
      { name: "status", comparator: "=", value: "draft" },
      { name: "status", comparator: "<>", value: "approved" },
    ]);
  });

  it("expands isAnyOf into equality checks sharing an OR group", () => {
    expect(filters(
      { field: "title", operator: "contains", value: "x" },
      { field: "status", operator: "isAnyOf", value: ["submitted", "in-review"] },
    )).toEqual([
      { name: "title", comparator: "ILIKE", value: "%x%" },
      { name: "status", comparator: "=", value: "submitted", group: "item1" },
      { name: "status", comparator: "=", value: "in-review", group: "item1" },
    ]);
    // An empty selection is a condition still being edited
    expect(filters({ field: "status", operator: "isAnyOf", value: [] })).toEqual([]);
  });

  it("maps doesNotContain to a negated case-insensitive match", () => {
    expect(filters({ field: "title", operator: "doesNotContain", value: "card" }))
      .toEqual([{ name: "title", comparator: "NOT ILIKE", value: "%card%" }]);
  });

  it("expands a negated date to the day's outside, ORed through a shared group", () => {
    const start = new Date("2026-07-01T00:00:00").toISOString();
    const end = new Date("2026-07-02T00:00:00").toISOString();
    expect(filters({ field: "created", operator: "not", value: "2026-07-01" })).toEqual([
      { name: "jcr:created", comparator: "<", value: start, group: "item0" },
      { name: "jcr:created", comparator: ">=", value: end, group: "item0" },
    ]);
  });

  it("skips conditions that cannot be applied server-side", () => {
    expect(filters(
      // Still being edited: no value yet
      { field: "title", operator: "contains" },
      { field: "title", operator: "contains", value: "" },
      // Unknown column and non-filterable column
      { field: "nonexistent", operator: "contains", value: "x" },
      { field: "schema", operator: "contains", value: "x" },
      // Operator with no server-side translation
      { field: "title", operator: "someFutureOperator", value: "x" },
    )).toEqual([]);
  });
});

describe("withServerFilterOperators", () => {
  it("only offers the operators the servlet supports", () => {
    const [title, status, created, amount] = withServerFilterOperators(COLUMNS);
    // The stock operator order is preserved, only the unsupported ones are dropped
    expect(title.filterOperators?.map(operator => operator.value)).toEqual(
      ["contains", "doesNotContain", "equals", "doesNotEqual", "startsWith", "endsWith",
        "isEmpty", "isNotEmpty", "isAnyOf"]);
    expect(status.filterOperators?.map(operator => operator.value)).toEqual(["is", "not", "isAnyOf"]);
    expect(created.filterOperators?.map(operator => operator.value)).toEqual(
      ["is", "not", "after", "onOrAfter", "before", "onOrBefore", "isEmpty", "isNotEmpty"]);
    expect(amount.filterOperators?.map(operator => operator.value)).toEqual(
      ["=", "!=", ">", ">=", "<", "<=", "isEmpty", "isNotEmpty", "isAnyOf"]);
  });
});
