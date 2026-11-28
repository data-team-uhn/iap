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

// Covers reading one value from a list and back, and reporting a pick.

import { changeAndCommit, countProblems, fromSingle, singleValue } from "@iap/frontend-commons/inputs/values";

describe("values", () => {
  it("reads one value from a list, and back", () => {
    expect(singleValue([])).toBe("");
    expect(singleValue([ "a", "b" ])).toBe("a");
    expect(fromSingle("")).toEqual([]);
    expect(fromSingle("a")).toEqual([ "a" ]);
  });

  it("reports a pick as a change, and commits it when the caller listens", () => {
    const onChange = vi.fn();
    const onCommit = vi.fn();

    changeAndCommit({ onChange, onCommit })([ "a" ]);
    changeAndCommit({ onChange })([ "b" ]);

    expect(onChange.mock.calls).toEqual([ [ [ "a" ] ], [ [ "b" ] ] ]);
    expect(onCommit.mock.calls).toEqual([ [ [ "a" ] ] ]);
  });
});

describe("countProblems", () => {
  it("accepts any number by default", () => {
    expect(countProblems(100)).toEqual([]);
  });

  it("asks for more values when a list holds fewer than its minimum", () => {
    expect(countProblems(1, 2)).toEqual([ "At least 2 values are needed." ]);
  });

  it("asks for nothing of an empty list", () => {
    expect(countProblems(0, 2)).toEqual([]);
  });

  it("says how many values are accepted when a list holds too many", () => {
    expect(countProblems(3, 0, 2)).toEqual([ "At most 2 values are accepted." ]);
  });

  it("speaks of one value in the singular", () => {
    expect(countProblems(2, 0, 1)).toEqual([ "At most 1 value is accepted." ]);
  });
});
