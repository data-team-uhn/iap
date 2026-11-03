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

import { SEVERITIES, type ReviewNote } from "./reviewData";

// PRETOTYPE. The REB's letter of review, drafted from the issues the reviewers raised, the most serious first.

export interface LetterInput {
  title: string;
  identifier: string;
  pi: string;
  // What each issue is about, as the study team would recognize it
  about: (note: ReviewNote) => string;
  issues: ReviewNote[];
  reviewer: string;
}

const HEADINGS: Record<string, string> = {
  critical: "Must be resolved before the study can go ahead",
  major: "Must be addressed before approval",
  medium: "Should be addressed before approval",
  minor: "Worth fixing",
  suggestion: "Suggestions",
};

// What an issue is about, leading into it: a question already ends a sentence
export function leadIn(where: string): string {
  return !where ? "" : /[?.!]$/.test(where) ? `${where} ` : `${where}. `;
}

export function draftLetter({ title, identifier, pi, about, issues, reviewer }: LetterInput): string {
  const date = new Date().toLocaleDateString("en-CA", { year: "numeric", month: "long", day: "numeric" });
  const lines = [
    date,
    "",
    `To ${pi}`,
    "",
    `Re: ${title} (${identifier})`,
    "",
    "Thank you for your submission. The Research Ethics Board has reviewed it and asks that you respond to the "
      + "points below. Please answer each one, revise the submission where it applies, and send it back.",
  ];
  let number = 1;
  for (const severity of SEVERITIES) {
    const these = issues.filter(issue => (issue.severity ?? "medium") === severity);
    if (these.length === 0) {
      continue;
    }
    lines.push("", HEADINGS[severity], "");
    for (const issue of these) {
      const where = about(issue);
      lines.push(`${number}. ${leadIn(where)}${issue.text}`);
      if (issue.passage?.quote) {
        lines.push(`   The protocol says: "${issue.passage.quote}"`);
      }
      number++;
    }
  }
  lines.push("", "Sincerely,", "", reviewer, "On behalf of the Research Ethics Board");
  return lines.join("\n");
}
