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

// Authored under src/test to mirror the src/main layout (Maven convention). The
// aggregation step merges src/main and src/test into one tree, so `./escape`
// resolves to the copied source at run time. See CLAUDE.md.
import { escapeJQL } from "./escape";

describe("escapeJQL", () => {
  it("doubles single quotes so the value is safe inside a JQL string literal", () => {
    expect(escapeJQL("O'Brien")).toBe("O''Brien");
  });

  it("leaves quote-free strings unchanged", () => {
    expect(escapeJQL("plain value")).toBe("plain value");
  });

  it("coerces non-string input to a string", () => {
    expect(escapeJQL(42)).toBe("42");
  });
});
