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

import { citationParts } from "./tcps2";

describe("citationParts", () => {
  it("leaves a citation naming no TCPS 2 as it is", () => {
    expect(citationParts("UHN AI Hub guidance")).toEqual([ { text: "UHN AI Hub guidance" } ]);
  });

  it("finds an article, its page, and its place on the official site", () => {
    const [ lead, article, rest ] = citationParts("TCPS 2, Art. 5.5A(c); PHIPA");
    expect(lead).toEqual({ text: "TCPS 2, " });
    expect(article.text).toBe("Art. 5.5A");
    expect(article.place).toMatchObject({ label: "Article 5.5A", page: 96, find: "Article 5.5A" });
    expect(article.place?.web).toMatch(/chapter-5-privacy\.html#5a$/);
    expect(rest).toEqual({ text: "(c); PHIPA" });
  });

  it("finds a chapter's section, and both ends of a range", () => {
    expect(citationParts("TCPS 2, Ch. 5 §A")[1].place).toMatchObject({ label: "Chapter 5, Section A", page: 86 });
    const range = citationParts("TCPS 2 Art. 5.1-5.4").filter(part => part.place);
    expect(range.map(part => part.place?.label)).toEqual([ "Article 5.1", "Article 5.4" ]);
  });

  it("keeps numbers that are no article as plain text", () => {
    const parts = citationParts("TCPS 2, Art. 5.3; UHN Policy 1.40.028");
    expect(parts.filter(part => part.place)).toHaveLength(1);
    expect(parts.map(part => part.text).join("")).toBe("TCPS 2, Art. 5.3; UHN Policy 1.40.028");
  });
});
