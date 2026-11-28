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

import { draftLetter, letterText, remarksOf } from "./rebLetter";

const input = { title: "A study", identifier: "IA-00001", pi: "Dr. Kim", about: () => "", issues: [], risks: [],
  reviewer: "Sam Okafor", group: "research ethics" };

describe("the reviewer's own paragraphs in a letter", () => {
  it("are cut where a blank line is, with nothing for nothing", () => {
    expect(remarksOf("First.\n\n  Second,\nstill second.  \n\n\n")).toEqual([ "First.", "Second,\nstill second." ]);
    expect(remarksOf("  ")).toEqual([]);
    expect(remarksOf()).toEqual([]);
  });

  it("come after the points and before the closing, in the REB's letter and in the plain one", () => {
    for (const template of [ "uhnReb", undefined ]) {
      const letter = draftLetter({ ...input, template, remarks: "Please also send the updated budget." });
      expect(letter.remarks).toEqual([ "Please also send the updated budget." ]);
      const text = letterText(letter);
      expect(text.indexOf("Please also send the updated budget.")).toBeLessThan(text.indexOf("Sincerely,"));
    }
  });
});
