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

  it("maps number and date operators to range comparators, converting dates to ISO instants", () => {
    const result = filters(
      { field: "amount", operator: "!=", value: "3" },
      { field: "amount", operator: ">=", value: "5" },
      { field: "created", operator: "after", value: "2026-07-01T10:00" },
    );
    expect(result.slice(0, 2)).toEqual([
      { name: "amount", comparator: "<>", value: "3" },
      { name: "amount", comparator: ">=", value: "5" },
    ]);
    // The date column maps to its server-side property, and the local date-time input value is
    // converted to a full ISO instant
    expect(result[2].name).toBe("jcr:created");
    expect(result[2].comparator).toBe(">");
    expect(result[2].value).toBe(new Date("2026-07-01T10:00").toISOString());
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

  it("skips conditions that cannot be applied server-side", () => {
    expect(filters(
      // Still being edited: no value yet
      { field: "title", operator: "contains" },
      { field: "title", operator: "contains", value: "" },
      // Unknown column and non-filterable column
      { field: "nonexistent", operator: "contains", value: "x" },
      { field: "schema", operator: "contains", value: "x" },
      // Operator with no server-side translation
      { field: "title", operator: "isAnyOf", value: ["a", "b"] },
    )).toEqual([]);
  });
});

describe("withServerFilterOperators", () => {
  it("only offers the operators the servlet supports", () => {
    const [title, status, created, amount] = withServerFilterOperators(COLUMNS);
    // The stock operator order is preserved, only the unsupported ones are dropped
    expect(title.filterOperators?.map(operator => operator.value)).toEqual(
      ["contains", "equals", "doesNotEqual", "startsWith", "endsWith", "isEmpty", "isNotEmpty"]);
    expect(status.filterOperators?.map(operator => operator.value)).toEqual(["is", "not"]);
    expect(created.filterOperators?.map(operator => operator.value)).toEqual(
      ["is", "not", "after", "onOrAfter", "before", "onOrBefore", "isEmpty", "isNotEmpty"]);
    expect(amount.filterOperators?.map(operator => operator.value)).toEqual(
      ["=", "!=", ">", ">=", "<", "<=", "isEmpty", "isNotEmpty"]);
  });
});
