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

import { markReadUrl, parseNotification } from "@iap/stored-notifications/notificationsModel";

describe("parseNotification", () => {
  it("reads what a row says", () => {
    expect(parseNotification({
      "@path": "/Notifications/aa/bb/cc/one",
      line: "Your request was approved",
      read: false,
      subject: "/Submissions/by-id/x",
      "jcr:created": "2026-09-08T10:00:00.000-04:00",
    })).toStrictEqual({
      path: "/Notifications/aa/bb/cc/one",
      line: "Your request was approved",
      read: false,
      subject: "/Submissions/by-id/x",
      created: "2026-09-08T10:00:00.000-04:00",
    });
  });

  it("accepts read as a boolean or as the string the serializer may send", () => {
    expect(parseNotification({ "@path": "/n", read: true }).read).toBe(true);
    expect(parseNotification({ "@path": "/n", read: "true" }).read).toBe(true);
    expect(parseNotification({ "@path": "/n", read: false }).read).toBe(false);
    expect(parseNotification({ "@path": "/n", read: "false" }).read).toBe(false);
  });

  it("leaves out what it cannot read, rather than inventing it", () => {
    // A notification whose subject was deleted, and one the serializer sent without a line
    const sparse = parseNotification({ "@path": "/n" });
    expect(sparse.line).toBe("");
    expect(sparse.read).toBe(false);
    expect(sparse.subject).toBeUndefined();
    expect(sparse.created).toBeUndefined();
  });

  it("ignores values of the wrong type", () => {
    const odd = parseNotification({ "@path": "/n", line: 7, subject: [ "/a" ], "jcr:created": 0 });
    expect(odd.line).toBe("");
    expect(odd.subject).toBeUndefined();
    expect(odd.created).toBeUndefined();
  });
});

describe("markReadUrl", () => {
  it("keeps the json extension, without which Sling matches no servlet", () => {
    expect(markReadUrl({ path: "/Notifications/aa/bb/cc/one", line: "", read: false }))
      .toBe("/Notifications/aa/bb/cc/one.markRead.json");
  });
});
