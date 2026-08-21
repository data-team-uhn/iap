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

import { faultLabel } from "@iap/error-tracking/errorGrid";
import { LOGGED_ERROR_TYPE, simpleName } from "@iap/error-tracking/errorTrackingApi";
import { getEntityTypeConfig } from "@iap/frontend-commons/entityGrid/registry";

// Importing the module registered the configuration as a side effect
const config = getEntityTypeConfig(LOGGED_ERROR_TYPE);

/** The column value getters only use the cell value, so they can be probed directly. */
function valueGetterOf(field: string): (value: unknown) => unknown {
  const getter = config?.columns.find(column => column.field === field)?.valueGetter;
  expect(getter).toBeDefined();
  return getter as unknown as (value: unknown) => unknown;
}

const columnOf = (field: string) => config?.columns.find(column => column.field === field);

describe("faultLabel", () => {
  it("names a problem by its phrase", () => {
    expect(faultLabel({ problem: "unknown comparator" })).toBe("unknown comparator");
  });

  it("names a failure by its throwable's simple class name", () => {
    // The package repeats down the whole column; the full name is on the error's own page
    expect(faultLabel({ type: "java.lang.IllegalStateException" })).toBe("IllegalStateException");
  });

  it("prefers the phrase, because only a problem has one", () => {
    expect(faultLabel({ problem: "unknown comparator", type: "java.lang.Throwable" }))
      .toBe("unknown comparator");
  });

  it("is empty when the row carries neither", () => {
    expect(faultLabel({})).toBe("");
    // A blank phrase is no phrase, and must not hide the throwable
    expect(faultLabel({ problem: "", type: "java.io.IOException" })).toBe("IOException");
  });
});

describe("simpleName", () => {
  it("drops the package", () => {
    expect(simpleName("io.uhndata.iap.tags.internal.TagPropagationEditor")).toBe("TagPropagationEditor");
  });

  it("leaves an unqualified name alone", () => {
    expect(simpleName("TagPropagationEditor")).toBe("TagPropagationEditor");
  });

  it("is empty for anything that is not a string", () => {
    // The grid types a value getter's parameter from the row, so this really is reached with
    // undefined for an error that named no component
    expect(simpleName(undefined)).toBe("");
    expect(simpleName(42)).toBe("");
  });
});

describe("the registered recorded-error grid configuration", () => {
  it("lists the errors from their own homepage, newest fault first", () => {
    expect(config?.homepage).toBe("/LoggedErrors");
    // What has just started happening is what somebody triaging wants to see
    expect(config?.defaultSort).toEqual({ field: "lastOccurrence", sort: "desc" });
  });

  it("links each row to its console page, not to its repository path", () => {
    // The two are different strings on purpose; the error is triaged at a console route
    expect(config?.rowLink?.({ "@name": "abc", "@path": "/LoggedErrors/abc" })).toBe("/admin/errors/abc");
  });

  it("links nowhere for a row with no fingerprint to address", () => {
    expect(config?.rowLink?.({})).toBeUndefined();
  });

  it("shows the fault, which is computed and so cannot be sorted or filtered server-side", () => {
    const fault = columnOf("fault");
    expect(fault?.sortable).toBe(false);
    expect(fault?.filterable).toBe(false);
  });

  it("falls back to the fingerprint on the card, which cannot be left blank", () => {
    // The card's title is its tap target; an untitled card would be unreachable
    expect(columnOf("fault")?.cardValue?.({ "@name": "abc" })).toBe("abc");
    expect(columnOf("fault")?.cardValue?.({ "@name": "abc", problem: "boom" })).toBe("boom");
  });

  it("shows the component unqualified", () => {
    expect(valueGetterOf("component")("io.uhndata.iap.Foo")).toBe("Foo");
  });

  it("offers the triage markers as a choice, and does not pretend they can be ordered", () => {
    const triage = columnOf("computedTags");
    expect(triage?.type).toBe("singleSelect");
    // Ordering by a multivalued property has no meaningful semantics
    expect(triage?.sortable).toBe(false);
    expect(triage?.renderCell).toBeDefined();
  });

  it("turns the timestamps into dates the grid can order and format", () => {
    expect(valueGetterOf("lastOccurrence")("2026-08-20T18:30:00.000+00:00")).toBeInstanceOf(Date);
    expect(valueGetterOf("jcr:created")("2026-08-01T10:00:00.000+00:00")).toBeInstanceOf(Date);
    // An error that somehow carries no timestamp must not become an Invalid Date
    expect(valueGetterOf("lastOccurrence")(undefined)).toBeNull();
  });

  it("keeps the last-seen day on the narrow-screen card and leaves first-seen off it", () => {
    // One timestamp dates the card; a second would only crowd it
    expect(columnOf("lastOccurrence")?.cardValue?.({ lastOccurrence: "2026-08-20T18:30:00.000+00:00" }))
      .toEqual(expect.any(String));
    expect(columnOf("jcr:created")?.cardSlot).toBe("omit");
  });
});
