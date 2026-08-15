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

import { describeOutcome, failureMessage } from "@iap/deletion/archiveOutcome";

describe("describeOutcome", () => {
  it("counts what a restore put back", () => {
    const outcome = describeOutcome({ status: "restored", restored: [ "/a", "/b" ] });
    expect(outcome.severity).toBe("success");
    expect(outcome.message).toContain("2 items");
  });

  it("says one item in the singular", () => {
    expect(describeOutcome({ status: "restored", restored: [ "/a" ] }).message).toContain("1 item to");
  });

  it("copes with a restore that reported no paths", () => {
    expect(describeOutcome({ status: "restored" }).message).toContain("0 items");
  });

  it("reports a purge as permanent", () => {
    const outcome = describeOutcome({ status: "deleted" });
    expect(outcome.severity).toBe("success");
    expect(outcome.message).toContain("permanently removed");
  });

  it("names what is in the way of a restore, with the reason", () => {
    const outcome = describeOutcome({
      status: "conflict",
      conflicts: [ { originalPath: "/content/x", reason: "OCCUPIED" } ],
    });
    expect(outcome.severity).toBe("warning");
    expect(outcome.message).toContain("/content/x (OCCUPIED)");
  });

  it("falls back to the server's sentence when a conflict lists nothing", () => {
    const outcome = describeOutcome({ status: "conflict", "status.message": "Something is in the way" });
    expect(outcome.message).toBe("Something is in the way");
  });

  it("has wording of its own when a conflict explains nothing at all", () => {
    expect(describeOutcome({ status: "conflict" }).message).toContain("in the way");
  });

  it("prefers the guard's own reason for a veto", () => {
    // The server knows why it refused; inventing a sentence here could describe the wrong reason
    const outcome = describeOutcome({ status: "vetoed", "status.message": "Archived less than 30 days ago" });
    expect(outcome.severity).toBe("warning");
    expect(outcome.message).toBe("Archived less than 30 days ago");
  });

  it("still says something when a veto explains nothing", () => {
    expect(describeOutcome({ status: "vetoed" }).message).toContain("guard refused");
  });

  it("treats a refused request as an error the user can read", () => {
    expect(describeOutcome({ status: "invalid" }).severity).toBe("error");
    expect(describeOutcome({ status: "invalid", "status.message": "Not an entry" }).message).toBe("Not an entry");
  });

  it("treats a server failure as an error", () => {
    expect(describeOutcome({ status: "failed" }).severity).toBe("error");
    expect(describeOutcome({ status: "failed", "status.message": "Boom" }).message).toBe("Boom");
  });
});

describe("failureMessage", () => {
  it("uses what the error itself says", () => {
    expect(failureMessage(new Error("The archive could not be listed (500)"), "fallback"))
      .toBe("The archive could not be listed (500)");
  });

  it("falls back when something other than an error was thrown", () => {
    expect(failureMessage("a bare string", "fallback")).toBe("fallback");
  });
});
