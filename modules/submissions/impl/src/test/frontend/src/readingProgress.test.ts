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
import { describe, expect, it } from "vitest";

import { getFailedPhase, getPhase } from "@iap/submissions/readingProgress";

describe("getPhase", () => {
  it("stays on parsing until the daemon has answered", () => {
    expect(getPhase({ parsed: false, reading: false }, 0)).toBe("parsing");
    expect(getPhase({}, 60_000)).toBe("parsing");
  });

  it("moves to digesting once the parse has ended and nobody has taken the reading", () => {
    expect(getPhase({ parsed: true, reading: false }, 0)).toBe("digesting");
  });

  it("spends a while extracting, then checks, after a job has the reading", () => {
    expect(getPhase({ parsed: true, reading: true }, 0)).toBe("extraction");
    expect(getPhase({ parsed: true, reading: true }, 44_000)).toBe("extraction");
    expect(getPhase({ parsed: true, reading: true }, 45_000)).toBe("validation");
  });
});

describe("getFailedPhase", () => {
  it("is unset while the reading is still going", () => {
    expect(getFailedPhase({ status: "running", parsed: false })).toBeUndefined();
  });

  it("stops on parsing when the daemon rejected the document", () => {
    expect(getFailedPhase({ status: "failed", retryable: true, parsed: false })).toBe("parsing");
    expect(getFailedPhase({ status: "failed", parsed: false })).toBe("parsing");
  });

  it("stops on extraction when the model was the part that failed", () => {
    expect(getFailedPhase({ status: "failed", parsed: true })).toBe("extraction");
    expect(getFailedPhase({ status: "failed" })).toBe("extraction");
  });
});
