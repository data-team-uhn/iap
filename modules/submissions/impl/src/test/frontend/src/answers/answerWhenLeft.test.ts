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

import type { FocusEvent } from "react";

import { answerWhenLeft } from "@iap/submissions/answers/answerWhenLeft";

// A field being left, holding the value given; `badInput` when the browser could not parse its text
const leaving = (value: string, badInput = false) =>
  ({ target: { value, validity: { badInput } } }) as unknown as FocusEvent<HTMLInputElement>;

describe("answerWhenLeft", () => {
  it("answers the value of the field", () => {
    const onAnswered = vi.fn();

    answerWhenLeft(onAnswered)(leaving("12"));

    expect(onAnswered).toHaveBeenCalledWith([ "12" ]);
  });

  it("answers a cleared field with no value", () => {
    const onAnswered = vi.fn();

    answerWhenLeft(onAnswered)(leaving(""));

    expect(onAnswered).toHaveBeenCalledWith([]);
  });

  it("does not answer text the browser could not parse, which would clear the stored answer", () => {
    const onAnswered = vi.fn();

    answerWhenLeft(onAnswered)(leaving("", true));

    expect(onAnswered).not.toHaveBeenCalled();
  });
});
