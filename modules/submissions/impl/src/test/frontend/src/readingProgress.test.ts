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

import { phaseFor, segmentFills } from "@iap/submissions/readingProgress";

describe("phaseFor", () => {
  it("stays on parsing until the daemon has answered", () => {
    expect(phaseFor({ parsed: false, reading: false }, 0)).toBe("parsing");
    expect(phaseFor({}, 60_000)).toBe("parsing");
  });

  it("moves to digesting once the parse has ended and nobody has taken the reading", () => {
    expect(phaseFor({ parsed: true, reading: false }, 0)).toBe("digesting");
  });

  it("spends a while extracting, then checks, after a job has the reading", () => {
    expect(phaseFor({ parsed: true, reading: true }, 0)).toBe("extraction");
    expect(phaseFor({ parsed: true, reading: true }, 44_000)).toBe("extraction");
    expect(phaseFor({ parsed: true, reading: true }, 45_000)).toBe("validation");
  });
});

describe("segmentFills", () => {
  it("fills only the current segment, and never all the way", () => {
    const fills = segmentFills("parsing", 90_000);
    expect(fills[0]).toBeGreaterThan(50);
    expect(fills[0]).toBeLessThan(100);
    expect(fills.slice(1)).toEqual([ 0, 0, 0 ]);
  });

  it("leaves earlier segments full", () => {
    expect(segmentFills("extraction", 0)).toEqual([ 100, 100, 0, 0 ]);
  });

  it("starts a segment empty", () => {
    expect(segmentFills("validation", 0)[3]).toBe(0);
  });
});
