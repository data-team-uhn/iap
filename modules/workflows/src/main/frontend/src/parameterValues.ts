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

// The values a parameter holds, as the properties panel keeps them while they are edited.

import type { Parameter } from "./workflowTypes";

// One element of a structure: the values of each of its fields, by field name.
export type StructureRecord = Partial<Record<string, ParameterValue[]>>;

// A value of any other type is the text a diagram writes it as.
export type ParameterValue = string | StructureRecord;

// What a record holds for one field, nothing when it leaves the field out.
export const valuesOf = (record: StructureRecord, field: string): ParameterValue[] => record[field] ?? [];

export const isRecordValue = (value: ParameterValue): value is StructureRecord => typeof value !== "string";

// What the server reads out of values being edited. Text is trimmed and a blank value dropped, at every
// level. A record left with nothing in it is dropped too: it is one that was added and never filled in.
export function settled(values: readonly ParameterValue[]): ParameterValue[] {
  return values.flatMap((value): ParameterValue[] => {
    if (!isRecordValue(value)) {
      const trimmed = value.trim();
      return trimmed === "" ? [] : [ trimmed ];
    }
    const fields = Object.keys(value).map(name => [ name, settled(valuesOf(value, name)) ] as const)
      .filter(([ , held ]) => held.length > 0);
    return fields.length === 0 ? [] : [ Object.fromEntries(fields) ];
  });
}

// Whether two records hold the same values. A field holding nothing is the same as a field left out.
export function sameRecord(left: StructureRecord, right: StructureRecord): boolean {
  const names = new Set([ ...Object.keys(left), ...Object.keys(right) ]);
  return [ ...names ].every(name => sameValues(valuesOf(left, name), valuesOf(right, name)));
}

export function sameValues(left: readonly ParameterValue[], right: readonly ParameterValue[]): boolean {
  return left.length === right.length && left.every((value, index) => {
    const other = right[index];
    return isRecordValue(value) && isRecordValue(other) ? sameRecord(value, other) : value === other;
  });
}

// The values of a parameter that holds text, for a field that edits text.
export const textValues = (values: readonly ParameterValue[]): string[] =>
  values.filter((value): value is string => !isRecordValue(value));

// The one value a field of a single value shows, empty when there is none.
export const singleValue = (values: readonly string[]): string => values.at(0) ?? "";

// What a field of a single value holds once something is typed or picked in it. Emptied, it holds nothing.
export const fromSingle = (value: string): string[] => value === "" ? [] : [ value ];

// The items of a list written as one value joined with commas, trimmed, with blank ones dropped.
export const splitList = (value: string): string[] => value.split(",").map(item => item.trim()).filter(Boolean);

// The values of a structure, for the field that edits one.
export const recordValues = (values: readonly ParameterValue[]): StructureRecord[] => values.filter(isRecordValue);

// A whole number as the server reads one, a sign and then digits, or the start of one as it is typed.
const WHOLE = /^([+-]?)(\d*)$/;

export const isWholeSoFar = (value: string): boolean => WHOLE.test(value);

// Whether text is a whole number the server can store, which is a 64-bit one.
function isLong(value: string): boolean {
  const [ , sign, written = "" ] = WHOLE.exec(value) ?? [];
  const digits = written.replace(/^0+(?=\d)/, "");
  const limit = sign === "-" ? "9223372036854775808" : "9223372036854775807";
  return digits !== "" && (digits.length < limit.length || digits.length === limit.length && digits <= limit);
}

// What the server stores as true or false, in any case. It drops any other word.
export const isBoolean = (value: string): boolean => /^(true|false)$/i.test(value);

// What the server stores a value as, for the types that can refuse one by its text alone. Text takes
// anything, and a reference or a principal is checked against the repository, which only the server can do.
const SHAPES: ReadonlyMap<string, { accepts: (value: string) => boolean; noun: string }> = new Map([
  [ "boolean", { accepts: isBoolean, noun: "true or false" } ],
  [ "long", { accepts: isLong, noun: "a whole number" } ],
]);

// Why the server would drop a value, or nothing when it would store it. A blank value is not refused
// but left out, as a value never given.
export function refusal(parameter: Parameter, value: string): string | undefined {
  const trimmed = value.trim();
  const shape = SHAPES.get(parameter.dataType);
  return trimmed === "" || shape === undefined || shape.accepts(trimmed) ? undefined
    : `"${trimmed}" is not ${shape.noun}, so it will be ignored`;
}

// The values the server would store. A record counts once anything is in it.
export const usableValues = (parameter: Parameter, values: readonly ParameterValue[]): ParameterValue[] =>
  settled(values).filter(value => isRecordValue(value) || refusal(parameter, value) === undefined);
