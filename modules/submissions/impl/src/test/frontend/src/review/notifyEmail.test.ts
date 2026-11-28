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

import { emailText, issuesEmail } from "./notifyEmail";

import type { ReviewNote } from "./reviewData";

const issue = (id: string, text: string, nature: ReviewNote["nature"], severity?: string): ReviewNote => ({
  id, kind: "issue", text, author: "me", created: "", mine: true, subjects: [], replies: [], nature, severity,
});

const input = {
  review: "Digital review", identifier: "IA-00004", title: "A study", pi: "Dr. Mancillas",
  about: (note: ReviewNote) => (note.id === "a" ? "Where is the data hosted?" : ""),
};

describe("issuesEmail", () => {
  it("names the review and what it asks in the subject, from the platform to the study team", () => {
    const email = issuesEmail({ ...input, issues: [ issue("a", "Name the host.", "question"),
      issue("b", "Encrypt it.", "required-change", "major"), issue("c", "Say who.", "question") ] });

    expect(email.subject).toBe("Digital review: 1 required change and 2 questions on IA-00004");
    expect(email.from).toBe("Research review notifications");
    expect(email.to).toBe("Dr. Mancillas and the study team");
  });

  it("lists every item in full, the required changes first, each with what it is about", () => {
    const email = issuesEmail({ ...input, issues: [ issue("a", "Name the host.", "question"),
      issue("b", "Encrypt it.", "required-change", "major") ] });

    expect(email.sections).toEqual([
      { heading: "Required changes", points: [ "1. (major) Encrypt it." ] },
      { heading: "Questions", points: [ "1. Where is the data hosted?: Name the host." ] },
    ]);
  });

  it("lists the suggestions last, without a severity, as only required changes have one", () => {
    const email = issuesEmail({ ...input, issues: [ issue("d", "Consider a pilot.", "suggestion", "minor"),
      issue("a", "Name the host.", "question") ] });

    expect(email.subject).toBe("Digital review: 1 question and 1 suggestion on IA-00004");
    expect(email.sections.map(section => section.heading)).toEqual([ "Questions", "Suggestions" ]);
    expect(email.sections[1].points).toEqual([ "1. Consider a pilot." ]);
  });

  it("counts an issue without a nature as a required change", () => {
    expect(issuesEmail({ ...input, issues: [ issue("b", "Encrypt it.", undefined) ] }).subject)
      .toBe("Digital review: 1 required change on IA-00004");
  });

  it("keeps the subject at the top of the text sent", () => {
    const text = emailText(issuesEmail({ ...input, issues: [ issue("a", "Name the host.", "question") ] }));

    expect(text.split("\n").slice(0, 3)).toEqual([ "Subject: Digital review: 1 question on IA-00004", "",
      "Dear Dr. Mancillas and the study team," ]);
    expect(text).toContain("Questions\n1. Where is the data hosted?: Name the host.");
  });
});
