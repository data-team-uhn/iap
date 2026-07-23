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

// Bridges the data grid's column filters to the pagination servlet's property filters. The
// servlet only ANDs conditions together and supports a fixed comparator set, so only the
// matching subset of the grid's stock operators is offered on each column, and the chosen
// filters are translated into name/comparator/value triples.

import {
  type GridFilterItem,
  type GridFilterModel,
  getGridDateOperators,
  getGridNumericOperators,
  getGridSingleSelectOperators,
  getGridStringOperators,
} from "@mui/x-data-grid-pro";

import type { PropertyFilter } from "./pagination";
import type { EntityGridColumn } from "./registry";

// The stock grid operators (per column type) that the servlet can honor. Notable exclusions:
// isAnyOf needs OR, doesNotContain needs NOT LIKE.
const SUPPORTED_OPERATORS: Record<string, string[]> = {
  string: ["contains", "startsWith", "endsWith", "equals", "doesNotEqual", "isEmpty", "isNotEmpty"],
  number: ["=", "!=", ">", ">=", "<", "<=", "isEmpty", "isNotEmpty"],
  date: ["is", "not", "after", "onOrAfter", "before", "onOrBefore", "isEmpty", "isNotEmpty"],
  dateTime: ["is", "not", "after", "onOrAfter", "before", "onOrBefore", "isEmpty", "isNotEmpty"],
  singleSelect: ["is", "not"],
};

// Grid operators that stand on their own, without a value to compare against.
const VALUELESS_OPERATORS: Record<string, string> = {
  isEmpty: "IS NULL",
  isNotEmpty: "IS NOT NULL",
};

// Grid operators that translate to a plain servlet comparator on the unchanged value.
const PLAIN_OPERATORS: Record<string, string> = {
  "equals": "=",
  "is": "=",
  "=": "=",
  "doesNotEqual": "<>",
  "not": "<>",
  "!=": "<>",
  ">": ">",
  ">=": ">=",
  "<": "<",
  "<=": "<=",
  "after": ">",
  "onOrAfter": ">=",
  "before": "<",
  "onOrBefore": "<=",
};

// Grid operators that translate to a case-insensitive LIKE, with the value placed into a
// wildcard pattern. Any % or _ the user types acts as an extra wildcard — the escaping needed
// to make them literal would not survive the servlet's own quoting.
const LIKE_OPERATORS: Record<string, (value: string) => string> = {
  contains: value => `%${value}%`,
  startsWith: value => `${value}%`,
  endsWith: value => `%${value}`,
};

function toStoredValue(item: GridFilterItem, column: EntityGridColumn): string {
  // Date pickers produce local date(-time) strings; the repository compares full ISO instants
  if ((column.type === "date" || column.type === "dateTime") && !Number.isNaN(Date.parse(String(item.value)))) {
    return new Date(String(item.value)).toISOString();
  }
  return String(item.value);
}

function toPropertyFilter(item: GridFilterItem, column: EntityGridColumn | undefined): PropertyFilter | undefined {
  if (!column || column.filterable === false) {
    return undefined;
  }
  // Like for sorting, sortProperty overrides which server-side property a column maps to
  const name = column.sortProperty ?? column.field;
  if (item.operator in VALUELESS_OPERATORS) {
    return { name, comparator: VALUELESS_OPERATORS[item.operator], value: "" };
  }
  // An item without a value is one the user is still editing, not a condition yet
  if (item.value == undefined || item.value === "") {
    return undefined;
  }
  if (item.operator in LIKE_OPERATORS) {
    return { name, comparator: "ILIKE", value: LIKE_OPERATORS[item.operator](String(item.value)) };
  }
  if (item.operator in PLAIN_OPERATORS) {
    return { name, comparator: PLAIN_OPERATORS[item.operator], value: toStoredValue(item, column) };
  }
  return undefined;
}

// Translates the grid's column filters into servlet property filters. Unknown columns,
// unsupported operators, and still-empty conditions are skipped.
export function toPropertyFilters(model: GridFilterModel, columns: EntityGridColumn[]): PropertyFilter[] {
  return (model.items ?? [])
    .map(item => toPropertyFilter(item, columns.find(column => column.field === item.field)))
    .filter((filter): filter is PropertyFilter => filter != undefined);
}

function stockOperators(type: EntityGridColumn["type"]) {
  switch (type) {
    case "number": return getGridNumericOperators();
    case "date": return getGridDateOperators();
    case "dateTime": return getGridDateOperators(true);
    case "singleSelect": return getGridSingleSelectOperators();
    default: return getGridStringOperators();
  }
}

function serverFilterOperators(type: EntityGridColumn["type"]) {
  const supported = SUPPORTED_OPERATORS[type ?? "string"] ?? SUPPORTED_OPERATORS.string;
  return stockOperators(type).filter(operator => supported.includes(operator.value));
}

// Narrows every column's filter operators down to the ones the servlet can honor, so the filter
// panel never offers a condition that silently cannot be applied.
export function withServerFilterOperators(columns: EntityGridColumn[]): EntityGridColumn[] {
  return columns.map(column => ({ ...column, filterOperators: serverFilterOperators(column.type) }));
}
