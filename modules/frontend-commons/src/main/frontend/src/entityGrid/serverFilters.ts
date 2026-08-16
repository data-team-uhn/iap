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

import ColoredChipsFilterInput from "./ColoredChipsFilterInput";

import type { PropertyFilter } from "./pagination";
import type { EntityGridColumn } from "./registry";

// The stock grid operators (per column type) that the servlet can honor. Operators needing OR
// (isAnyOf, "not" on a day interval) expand into conditions sharing an OR group.
const SUPPORTED_OPERATORS: Record<string, string[]> = {
  string: ["contains", "doesNotContain", "equals", "doesNotEqual", "startsWith", "endsWith",
    "isEmpty", "isNotEmpty", "isAnyOf"],
  number: ["=", "!=", ">", ">=", "<", "<=", "isEmpty", "isNotEmpty", "isAnyOf"],
  date: ["is", "not", "after", "onOrAfter", "before", "onOrBefore", "isEmpty", "isNotEmpty"],
  dateTime: ["is", "not", "after", "onOrAfter", "before", "onOrBefore", "isEmpty", "isNotEmpty"],
  singleSelect: ["is", "not", "isAnyOf"],
};

// Grid operators that stand on their own, without a value to compare against.
const VALUELESS_OPERATORS: Record<string, string> = {
  isEmpty: "IS NULL",
  isNotEmpty: "IS NOT NULL",
};

// Text typed by a user, placed into a LIKE pattern so that it matches itself: the pattern
// language's own three characters are escaped, since somebody searching for "50%" or a Windows
// path means those literally rather than as wildcards. Backslash goes first, or it would escape
// the escapes added after it. The servlet quotes the value by doubling apostrophes and leaves
// backslashes alone, so what is escaped here is what Oak's matcher sees.
const likeLiteral = (value: string): string => value.replace(/[\\%_]/g, "\\$&");

// How each value-carrying grid operator translates into a servlet filter: either a plain
// comparator on the unchanged value, or a (possibly negated) case-insensitive LIKE with the
// value placed into a wildcard pattern.
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

// The picked day as midnight in the user's own timezone. The grid's date filter input hands
// over a Date object it built from the YYYY-MM-DD input value — which JavaScript parses as UTC
// midnight — so the intended calendar day is recovered from the UTC fields; a plain YYYY-MM-DD
// string (e.g. from a programmatic filter model) is parsed as local directly.
function pickedDay(value: unknown): Date | undefined {
  const day = value instanceof Date ? value : new Date(`${String(value)}T00:00:00`);
  if (Number.isNaN(day.getTime())) {
    return undefined;
  }
  return value instanceof Date ? new Date(day.getUTCFullYear(), day.getUTCMonth(), day.getUTCDate()) : day;
}

// Expands a day-granularity date condition into instant comparisons against the day's
// boundaries, [start of day, start of next day) in the user's own timezone — stored dates are
// full instants, so "is July 1st" must mean "within July 1st", not "equals its first
// millisecond". This is why dates are filtered with day (not date-time) pickers. "not" means
// outside the day interval, so its two comparisons are ORed through a shared group.
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
  // Like for sorting, sortProperty overrides which server-side property a column maps to
  const name = column.sortProperty ?? column.field;
  // `hasOwn` rather than `in`: the operator is a string from the grid's model, and `in` would also
  // answer for everything Object.prototype carries, so an operator named "constructor" or
  // "toString" would look supported and be translated into nonsense.
  if (Object.hasOwn(VALUELESS_OPERATORS, item.operator)) {
    return [{ name, comparator: VALUELESS_OPERATORS[item.operator], value: "" }];
  }
  if (item.operator === "isAnyOf") {
    // "any of" is an OR over equality checks, expressed by sharing a group
    const values: unknown[] = Array.isArray(item.value) ? item.value : [];
    return values.map(value => ({ name, comparator: "=", value: String(value), group }));
  }
  // An item without a value is one the user is still editing, not a condition yet
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

// Translates the grid's column filters into servlet property filters. Unknown columns,
// unsupported operators, and still-empty conditions are skipped; a condition may expand to more
// than one property filter, either ANDed like everything else ("is <day>" becomes the day's two
// boundary comparisons) or ORed through a shared group ("is any of", "not <day>").
export function toPropertyFilters(model: GridFilterModel, columns: EntityGridColumn[]): PropertyFilter[] {
  return model.items
    .flatMap((item, index) =>
      toFilterTriples(item, columns.find(column => column.field === item.field), `item${index}`));
}

function stockOperators(type: EntityGridColumn["type"]) {
  if (type === "number") {
    return getGridNumericOperators();
  }
  // Both date types filter with day pickers: conditions are expanded to day boundaries, so a
  // time of day in the filter value would be meaningless (cells still display date + time)
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
    // On choice columns, values picked in "is any of" show as chips in their options' own
    // colors, matching how the grid's cells present them
    .map(operator => type === "singleSelect" && operator.value === "isAnyOf"
      ? { ...operator, InputComponent: ColoredChipsFilterInput }
      : operator);
}

// Narrows every column's filter operators down to the ones the servlet can honor, so the filter
// panel never offers a condition that silently cannot be applied.
export function withServerFilterOperators(columns: EntityGridColumn[]): EntityGridColumn[] {
  return columns.map(column => ({ ...column, filterOperators: serverFilterOperators(column.type) }));
}
