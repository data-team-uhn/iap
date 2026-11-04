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

import { type Candidate, createCandidateRegistry } from "@iap/frontend-commons/candidateRegistry";

const STORE_KEY = "__iapTestCandidates";
const UNRELATED_STORE_KEY = "__iapUnrelatedTestCandidates";

// Subjects and components are plain strings: the registry never looks inside either
const registry = createCandidateRegistry<string, string>(STORE_KEY);

describe("a candidate registry", () => {
  afterEach(() => {
    Reflect.deleteProperty(window, STORE_KEY);
    Reflect.deleteProperty(window, UNRELATED_STORE_KEY);
    vi.restoreAllMocks();
  });

  it("has nothing to offer until something registers", () => {
    expect(registry.get("anything")).toBeNull();
  });

  it("picks the candidate that is most confident", () => {
    registry.register(() => [ "general", 10 ]);
    registry.register(() => [ "narrow", 60 ]);
    registry.register(() => [ "middling", 40 ]);

    expect(registry.get("anything")).toBe("narrow");
  });

  // Registration order decides a tie, so which component wins does not depend on the order the
  // registry happens to visit its candidates in
  it("leaves a tie to whichever registered first", () => {
    registry.register(() => [ "first", 50 ]);
    registry.register(() => [ "second", 50 ]);

    expect(registry.get("anything")).toBe("first");
  });

  it("hands each candidate the subject, and passes over the ones that do not recognize it", () => {
    registry.register(() => null);
    registry.register(subject => (subject === "date" ? [ "calendar", 50 ] : null));

    expect(registry.get("text")).toBeNull();
    expect(registry.get("date")).toBe("calendar");
  });

  // Counted rather than resolved. A duplicate answers with the same component at the same
  // confidence, so asserting on the winner would hold whether or not anything deduplicated
  it("asks a candidate offered twice only once", () => {
    const candidate = vi.fn<Candidate<string, string>>(() => [ "only", 90 ]);
    registry.register(candidate);
    registry.register(candidate);

    expect(registry.get("anything")).toBe("only");
    expect(candidate).toHaveBeenCalledTimes(1);
  });

  it("forgets every candidate when cleared", () => {
    registry.register(() => [ "gone", 50 ]);

    registry.clear();

    expect(registry.get("anything")).toBeNull();
  });

  // A bundle built separately creates its own registry from its own copy of this module
  it("shares its candidates with every registry created on the same key", () => {
    const elsewhere = createCandidateRegistry<string, string>(STORE_KEY);

    elsewhere.register(() => [ "contributed", 50 ]);

    expect(registry.get("anything")).toBe("contributed");
  });

  it("keeps apart the registries created on different keys", () => {
    const unrelated = createCandidateRegistry<string, string>(UNRELATED_STORE_KEY);

    unrelated.register(() => [ "unrelated", 50 ]);

    expect(registry.get("anything")).toBeNull();
  });

  it("logs a candidate that throws, and picks among the others", () => {
    const logged = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const failure = new Error("this candidate is broken");
    registry.register(() => [ "weak", 10 ]);
    registry.register(() => {
      throw failure;
    });

    expect(registry.get("anything")).toBe("weak");
    expect(logged).toHaveBeenCalledWith(expect.stringContaining(STORE_KEY), failure);
  });
});
