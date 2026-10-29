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

import { commitProps, fromSingle, picker, singleValue } from "@iap/frontend-commons/inputs/valueInput";

describe("valueInput", () => {
  it("reads one value from a list, and back", () => {
    expect(singleValue([])).toBe("");
    expect(singleValue([ "a", "b" ])).toBe("a");
    expect(fromSingle("")).toEqual([]);
    expect(fromSingle("a")).toEqual([ "a" ]);
  });

  it("reports a pick as a change, and commits it when the caller listens", () => {
    const onChange = vi.fn();
    const onCommit = vi.fn();

    picker({ onChange, onCommit })([ "a" ]);
    picker({ onChange })([ "b" ]);

    expect(onChange.mock.calls).toEqual([ [ [ "a" ] ], [ [ "b" ] ] ]);
    expect(onCommit.mock.calls).toEqual([ [ [ "a" ] ] ]);
  });

  it("commits on Enter, not on other keys", () => {
    const commit = vi.fn();
    const { onKeyDown } = commitProps(commit);

    onKeyDown({ key: "a" });
    expect(commit).not.toHaveBeenCalled();
    onKeyDown({ key: "Enter" });
    expect(commit).toHaveBeenCalledOnce();
  });
});
