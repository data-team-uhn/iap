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

// Turns the data grid's column filters into the pagination servlet's property filters. Each column
// offers only the grid's stock operators that the servlet's comparators can express.

import {
  type GridFilterItem,
  type GridFilterModel,
  getGridDateOperators,
  getGridNumericOperators,
  getGridSingleSelectOperators,
  getGridStringOperators,
} from "@mui/x-data-grid-pro";

import ColoredChipsFilterInput from "./ColoredChipsFilterInput";
import { type EntityGridColumn, serverProperty } from "./registry";

import type { PropertyFilter } from "./pagination";

// The stock grid operators the servlet can express, by column type. An operator that needs an OR,
// such as isAnyOf, or "not" on a day, expands into conditions that share a group.
const SUPPORTED_OPERATORS: Record<string, string[]> = {
  string: ["contains", "doesNotContain", "equals", "doesNotEqual", "startsWith", "endsWith",
    "isEmpty", "isNotEmpty", "isAnyOf"],
  number: ["=", "!=", ">", ">=", "<", "<=", "isEmpty", "isNotEmpty", "isAnyOf"],
  date: ["is", "not", "after", "onOrAfter", "before", "onOrBefore", "isEmpty", "isNotEmpty"],
  dateTime: ["is", "not", "after", "onOrAfter", "before", "onOrBefore", "isEmpty", "isNotEmpty"],
  singleSelect: ["is", "not", "isAnyOf"],
};

// The grid operators that take no value, with the comparator each becomes.
const VALUELESS_OPERATORS: Record<string, string> = {
  isEmpty: "IS NULL",
  isNotEmpty: "IS NOT NULL",
};

// Text typed by a user, escaped for a LIKE pattern so that it matches itself: "50%" or a Windows
// path means those characters, not wildcards. The servlet binds the value into the query, so these
// escapes reach Oak's LIKE as written.
const likeLiteral = (value: string): string => value.replace(/[\\%_]/g, "\\$&");

// What each grid operator with a value becomes: a comparator on the value, or a case-insensitive
// LIKE, possibly negated, with the value inside a wildcard pattern.
const VALUED_OPERATORS: Record<string, (name: string, value: string) => PropertyFilter> = {
  "equals": (name, value) => ({ name, comparator: "=", value }),
  "is": (name, value) => ({ name, comparator: "=", value }),
  "=": (name, value) => ({ name, comparator: "=", value }),
  "doesNotEqual": (name, value) => ({ name, comparator: "<>", value }),
  "not": (name, value) => ({ name, comparator: "<>", value }),
  "!=": (name, value) => ({ name, comparator: "<>", value }),
  ">": (name, value) => ({ name, comparator: ">", value }),
  ">=": (name, value) => ({ name, comparator: ">=", value }),
  "<": (name, value) => ({ name, comparator: "<", value }),
  "<=": (name, value) => ({ name, comparator: "<=", value }),
  "contains": (name, value) => ({ name, comparator: "ILIKE", value: `%${likeLiteral(value)}%` }),
  "doesNotContain": (name, value) => ({ name, comparator: "NOT ILIKE", value: `%${likeLiteral(value)}%` }),
  "startsWith": (name, value) => ({ name, comparator: "ILIKE", value: `${likeLiteral(value)}%` }),
  "endsWith": (name, value) => ({ name, comparator: "ILIKE", value: `%${likeLiteral(value)}` }),
};

// The picked day as midnight in the user's own timezone; undefined if it is not a day. The grid's
// date input gives a Date it parsed from YYYY-MM-DD, and JavaScript parses that as midnight UTC.
// The calendar day is therefore read from the UTC fields. A YYYY-MM-DD string, from a filter model
// built in code, is read as local time directly.
function pickedDay(value: unknown): Date | undefined {
  const day = value instanceof Date ? value : new Date(`${String(value)}T00:00:00`);
  if (Number.isNaN(day.getTime())) {
    return undefined;
  }
  return value instanceof Date ? new Date(day.getUTCFullYear(), day.getUTCMonth(), day.getUTCDate()) : day;
}

// A condition on a day as comparisons with the day's bounds, from its start up to the start of the
// next day, in the user's own timezone. Stored dates are instants, so "is July 1st" means "during
// July 1st". "not" means before or after the day, so its two comparisons share a group.
function dayFilters(operator: string, name: string, value: unknown, group: string): PropertyFilter[] {
  const start = pickedDay(value);
  if (start == undefined) {
    return [];
  }
  const end = new Date(start);
  end.setDate(end.getDate() + 1);
  switch (operator) {
    case "is":
      return [
        { name, comparator: ">=", value: start.toISOString() },
        { name, comparator: "<", value: end.toISOString() },
      ];
    case "not":
      return [
        { name, comparator: "<", value: start.toISOString(), group },
        { name, comparator: ">=", value: end.toISOString(), group },
      ];
    case "after": return [{ name, comparator: ">=", value: end.toISOString() }];
    case "onOrAfter": return [{ name, comparator: ">=", value: start.toISOString() }];
    case "before": return [{ name, comparator: "<", value: start.toISOString() }];
    case "onOrBefore": return [{ name, comparator: "<", value: end.toISOString() }];
    default: return [];
  }
}

function toFilterTriples(item: GridFilterItem, column: EntityGridColumn | undefined, group: string): PropertyFilter[] {
  if (!column || column.filterable === false) {
    return [];
  }
  const name = serverProperty(column);
  // Not `in`, which also finds what Object.prototype carries, such as an operator named "toString"
  if (Object.hasOwn(VALUELESS_OPERATORS, item.operator)) {
    return [{ name, comparator: VALUELESS_OPERATORS[item.operator], value: "" }];
  }
  if (item.operator === "isAnyOf") {
    // "is any of" is one equality per value, all in one group
    const values: unknown[] = Array.isArray(item.value) ? item.value : [];
    return values.map(value => ({ name, comparator: "=", value: String(value), group }));
  }
  // A condition without a value is still being edited
  if (item.value == undefined || item.value === "") {
    return [];
  }
  if (column.type === "date" || column.type === "dateTime") {
    return dayFilters(item.operator, name, item.value, group);
  }
  if (Object.hasOwn(VALUED_OPERATORS, item.operator)) {
    return [VALUED_OPERATORS[item.operator](name, String(item.value))];
  }
  return [];
}

// The grid's column filters as servlet property filters, which the server ANDs. A condition on an
// unknown or unfilterable column, with an unsupported operator, or without a value yet, is left
// out. One condition can become several filters. "is <day>" becomes two comparisons, both required.
// "is any of" and "not <day>" become a group of their own, which the server ORs.
export function toPropertyFilters(model: GridFilterModel, columns: EntityGridColumn[]): PropertyFilter[] {
  return model.items
    .flatMap((item, index) =>
      toFilterTriples(item, columns.find(column => column.field === item.field), `item${index}`));
}

function stockOperators(type: EntityGridColumn["type"]) {
  if (type === "number") {
    return getGridNumericOperators();
  }
  // Both date types filter by the day, so they take the date operators, whose input picks a day
  if (type === "date" || type === "dateTime") {
    return getGridDateOperators();
  }
  if (type === "singleSelect") {
    return getGridSingleSelectOperators();
  }
  // Everything else, including untyped columns, filters as text
  return getGridStringOperators();
}

function serverFilterOperators(type: EntityGridColumn["type"]) {
  const named = type ?? "string";
  const supported = Object.hasOwn(SUPPORTED_OPERATORS, named) ? SUPPORTED_OPERATORS[named] : SUPPORTED_OPERATORS.string;
  return stockOperators(type)
    .filter(operator => supported.includes(operator.value))
    // On a choice column, the values picked in "is any of" show as chips in their options' colors
    .map(operator => type === "singleSelect" && operator.value === "isAnyOf"
      ? { ...operator, InputComponent: ColoredChipsFilterInput }
      : operator);
}

// Narrows every column's filter operators to the ones the servlet can express, so that the filter
// panel offers no condition that toPropertyFilters would leave out.
export function withServerFilterOperators(columns: EntityGridColumn[]): EntityGridColumn[] {
  return columns.map(column => ({ ...column, filterOperators: serverFilterOperators(column.type) }));
}
