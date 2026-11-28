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

import { aiPreReview, findingCounts } from "./aiFindings";

const VERSION = "/Schemas/study/v1";
const question = (path: string, text: string) => ({ "jcr:primaryType": "sch:Question", "@path": `${VERSION}/${path}`,
  text });
const answer = (path: string, text: string, modified: string) => ({ "jcr:primaryType": "sub:Answer",
  question: question(path, text), "jcr:lastModified": modified });

const submission = (findings: Record<string, unknown>[]) => ({
  schemaVersion: { "@path": VERSION },
  purpose: answer("source/purpose", "Why was it collected?", "2026-11-01T09:00:00Z"),
  threshold: answer("results/threshold", "Below how many people?", "2026-11-01T11:00:00Z"),
  screening: {
    "jcr:primaryType": "sub:Screening", "jcr:created": "2026-11-01T10:00:00Z", summary: "Mostly fine.",
    risk: {
      "jcr:primaryType": "sub:Assessment", topic: { text: "Reporting" },
      ...Object.fromEntries(findings.map((one, index) => [ `c${index}`,
        { "jcr:primaryType": "sub:Comment", "@path": `/s/screening/risk/c${index}`, text: `Finding ${index}`, ...one } ])),
    },
  },
});

describe("aiPreReview", () => {
  it("is undefined before the AI read the submission", () => {
    expect(aiPreReview({ schemaVersion: { "@path": VERSION } })).toBeUndefined();
  });

  it("names what each finding is about, the questions by their path and documents by their requirement", () => {
    const review = aiPreReview(submission([ { tags: [ "medium" ], suggestion: "Tick it", subjects: [
      answer("source/purpose", "Why was it collected?", "2026-11-01T09:00:00Z"),
      { "jcr:primaryType": "sub:Document", title: "x.pdf", fulfills: { label: "Protocol" } },
    ] } ]));
    expect(review?.summary).toBe("Mostly fine.");
    expect(review?.findings[0]).toMatchObject({ id: "/s/screening/risk/c0", topic: "Reporting", severity: "medium",
      suggestion: "Tick it", subjects: [ { label: "Why was it collected?", question: "source/purpose" },
        { label: "Protocol" } ], changed: false });
  });

  it("tells a finding changed when an answer it is about changed after the read", () => {
    const review = aiPreReview(submission([ { subjects: [ question("results/threshold", "Below how many people?") ] } ]));
    expect(review?.findings[0].changed).toBe(true);
  });

  it("reads the team's response, and counts what they did", () => {
    const review = aiPreReview(submission([
      { subjects: [ question("results/threshold", "Below") ] },
      { response: { "jcr:primaryType": "sub:Reply", "@name": "response", text: "On purpose" } },
      {},
    ]));
    expect(review?.findings[1].response).toBe("On purpose");
    expect(findingCounts(review?.findings ?? [])).toEqual({ changed: 1, responded: 1, left: 1 });
  });
});
