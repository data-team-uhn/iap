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

import { booleanChoices, choiceLabel, withCurrent } from "@iap/frontend-commons/inputs/choices";

const choices = [ { value: "a", label: "A" } ];

describe("choiceLabel", () => {
  it("labels a value by its choice, or else as it is", () => {
    expect(choiceLabel(choices, "a")).toBe("A");
    expect(choiceLabel(choices, "b")).toBe("b");
  });
});

describe("withCurrent", () => {
  it("adds the current value only when no choice holds it", () => {
    expect(withCurrent(choices, "a")).toBe(choices);
    expect(withCurrent(choices)).toBe(choices);
    expect(withCurrent(choices, "")).toBe(choices);
    expect(withCurrent(choices, "b")).toEqual([ ...choices, { value: "b", label: "b" } ]);
    expect(withCurrent(choices, "b", value => value.toUpperCase())).toEqual([ ...choices, { value: "b", label: "B" } ]);
  });
});

describe("booleanChoices", () => {
  it("stores true and false, labelled Yes and No unless told otherwise", () => {
    expect(booleanChoices()).toEqual([ { value: "true", label: "Yes" }, { value: "false", label: "No" } ]);
    expect(booleanChoices("On", "Off").map(choice => choice.label)).toEqual([ "On", "Off" ]);
  });
});
