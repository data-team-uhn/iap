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

import { getInputFrame, getOptionFrame } from "@iap/submissions/answers/answerFrame";

describe("answerFrame", () => {
  // The same border width either way, so confirming an answer does not shift the page
  it("frames an option the model picked, and keeps the space for one it did not", () => {
    const framed = getOptionFrame(true);
    const plain = getOptionFrame(false);

    expect(framed.border).toContain("dashed");
    expect(framed.background).not.toBe("none");
    expect(plain.border).toBe("2px solid transparent");
    expect(plain.background).toBe("none");
  });

  it("frames a typed-in answer only while it is the model's", () => {
    expect(getInputFrame(true)).toHaveProperty([ "& .MuiOutlinedInput-root" ]);
    expect(getInputFrame(false)).toEqual({});
  });
});
