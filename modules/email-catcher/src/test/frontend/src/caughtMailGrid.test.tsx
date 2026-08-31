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

import { CAUGHT_MESSAGE_TYPE } from "@iap/email-catcher/caughtMailApi";
import { addressLabel, subjectLabel } from "@iap/email-catcher/caughtMailGrid";
import { getEntityTypeConfig } from "@iap/frontend-commons/entityGrid/registry";

// Importing the module registered the configuration as a side effect
const config = getEntityTypeConfig(CAUGHT_MESSAGE_TYPE);

/** The column value getters only use the cell value, so they can be probed directly. */
function valueGetterOf(field: string): (value: unknown) => unknown {
  const getter = config?.columns.find(column => column.field === field)?.valueGetter;
  expect(getter).toBeDefined();
  return getter as unknown as (value: unknown) => unknown;
}

const columnOf = (field: string) => config?.columns.find(column => column.field === field);

describe("subjectLabel", () => {
  it("is the subject the message carries", () => {
    expect(subjectLabel("Your proposal has been approved")).toBe("Your proposal has been approved");
  });

  it("says a message has no subject rather than leaving the identifying cell blank", () => {
    // A blank cell in the column rows are recognised by reads as a broken grid
    expect(subjectLabel(undefined)).toBe("(no subject)");
    expect(subjectLabel("")).toBe("(no subject)");
  });
});

describe("addressLabel", () => {
  it("puts a list of addresses on one line", () => {
    expect(addressLabel([ "a@uhn.ca", "b@uhn.ca" ])).toBe("a@uhn.ca, b@uhn.ca");
  });

  it("reads a single address serialized as a bare string", () => {
    expect(addressLabel("a@uhn.ca")).toBe("a@uhn.ca");
  });

  it("passes over anything in the list that is not an address", () => {
    expect(addressLabel([ "a@uhn.ca", 42 ])).toBe("a@uhn.ca");
  });

  it("is empty when the message named nobody", () => {
    expect(addressLabel(undefined)).toBe("");
    expect(addressLabel([])).toBe("");
  });
});

describe("the registered caught-message grid configuration", () => {
  it("lists the messages from the catcher's own folder, newest first", () => {
    expect(config?.homepage).toBe("/CaughtMail");
    // What was just sent is what somebody came to look at
    expect(config?.defaultSort).toEqual({ field: "caughtAt", sort: "desc" });
  });

  it("links each row to its console page, not to its repository path", () => {
    expect(config?.rowLink?.({ "@name": "abc", "@path": "/CaughtMail/abc" })).toBe("/admin/mail/abc");
  });

  it("links nowhere for a row with no name to address", () => {
    expect(config?.rowLink?.({})).toBeUndefined();
    expect(config?.rowLink?.({ "@name": "" })).toBeUndefined();
  });

  it("does not pretend the address lists can be ordered", () => {
    // Ordering by a multivalued property has no meaningful semantics; filtering it does have one,
    // and is how somebody asks what was sent to a given person
    expect(columnOf("to")?.sortable).toBe(false);
    expect(columnOf("from")?.sortable).toBe(false);
    expect(columnOf("to")?.filterable).not.toBe(false);
  });

  it("shows the addresses joined, and the subject with its fallback", () => {
    expect(valueGetterOf("to")([ "a@uhn.ca", "b@uhn.ca" ])).toBe("a@uhn.ca, b@uhn.ca");
    expect(valueGetterOf("from")([ "iap@uhn.ca" ])).toBe("iap@uhn.ca");
    expect(valueGetterOf("subject")("Hello")).toBe("Hello");
    expect(valueGetterOf("subject")(undefined)).toBe("(no subject)");
  });

  it("turns the timestamp into a date the grid can order and format", () => {
    expect(valueGetterOf("caughtAt")("2026-08-20T18:30:00.000+00:00")).toBeInstanceOf(Date);
    // A message somehow written without one must not become an Invalid Date
    expect(valueGetterOf("caughtAt")(undefined)).toBeNull();
  });

  it("dates the narrow-screen card by the day, not the full timestamp", () => {
    const caption = columnOf("caughtAt")?.cardValue?.({ caughtAt: "2026-08-20T18:30:00.000+00:00" });
    expect(caption).toBe(new Date("2026-08-20T18:30:00.000+00:00").toLocaleDateString());
    expect(columnOf("caughtAt")?.cardValue?.({})).toBeUndefined();
  });
});
