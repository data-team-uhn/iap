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

import { isDecimalNumber, isDecimalSoFar, isWholeNumber, isWholeSoFar } from "@iap/frontend-commons/numberText";

describe("isWholeNumber", () => {
  it("takes digits, maybe signed", () => {
    expect([ "12", "-3", "+4", "0" ].every(isWholeNumber)).toBe(true);
  });

  it("refuses what is not one, or is too large to hold exactly", () => {
    // 2^53 + 1, which Number rounds to 2^53
    expect([ "1.5", "", "x", "0x10", "9007199254740993" ].some(isWholeNumber)).toBe(false);
  });
});

describe("isDecimalNumber", () => {
  it("takes decimals, with a point and an exponent if they have them", () => {
    expect([ "1.5", "-2", ".5", "5.", "1e3", "+2.5E-2" ].every(isDecimalNumber)).toBe(true);
  });

  it("refuses the literals Number reads that nobody types as a decimal", () => {
    expect([ "0x10", "0b11", "0o17", "Infinity", "1e999", "", " ", "1,5" ].some(isDecimalNumber)).toBe(false);
  });
});

describe("isWholeSoFar", () => {
  it("takes the start of a whole number", () => {
    expect([ "", "-", "+", "12", "-3" ].every(isWholeSoFar)).toBe(true);
  });

  it("refuses what no more typing makes whole", () => {
    expect([ "1.", "x", "1-", " 1", "--1" ].some(isWholeSoFar)).toBe(false);
  });
});

describe("isDecimalSoFar", () => {
  it("takes the start of a decimal number", () => {
    expect([ "", "-", ".", "-.", "1.", "1.5", ".5", "2e", "2e-", "+2.5E-2", ".5e3" ].every(isDecimalSoFar)).toBe(true);
  });

  it("refuses what no more typing makes a number", () => {
    expect([ "e", ".e", "1.2.3", "1e2.5", "1e2e", "x", "1,5" ].some(isDecimalSoFar)).toBe(false);
  });
});
