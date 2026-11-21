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

// PRETOTYPE. A reviewer's letter to the study team, drafted from the issues they raised, the most serious first.

export interface LetterInput {
  title: string;
  identifier: string;
  pi: string;
  // What each issue is about, as the study team would recognize it
  about: (note: ReviewNote) => string;
  issues: ReviewNote[];
  // The risks the issues are filed under, by path, in the order the review weighs them
  risks: { path: string; label: string }[];
  reviewer: string;
  // The reviewer group writing, such as "Research ethics"
  group: string;
}

const SEVERITY_NAMES: Record<string, string> = {
  critical: "Critical", major: "Major", medium: "Medium", minor: "Minor", suggestion: "Suggestion",
};

// What an issue is about, leading into it: a question already ends a sentence
export function leadIn(where: string): string {
  return !where ? "" : /[?.!]$/.test(where) ? `${where} ` : `${where}. `;
}

// The letter goes risk by risk, as the review weighs them, then the points filed under none; within each, the most
// serious first, each saying how serious
export function draftLetter({ title, identifier, pi, about, issues, risks, reviewer, group }: LetterInput): string {
  const date = new Date().toLocaleDateString("en-CA", { year: "numeric", month: "long", day: "numeric" });
  const lines = [
    date,
    "",
    `To ${pi}`,
    "",
    `Re: ${title} (${identifier})`,
    "",
    `Thank you for your submission. The ${group} review has looked at it and asks that you respond to the `
      + "points below. Please answer each one, revise the submission where it applies, and send it back.",
  ];
  const known = new Set(risks.map(risk => risk.path));
  const sections = [
    ...risks.map(risk => ({ heading: risk.label, issues: issues.filter(issue => issue.topicPath === risk.path) })),
    { heading: "Other points", issues: issues.filter(issue => !issue.topicPath || !known.has(issue.topicPath)) },
  ];
  let number = 1;
  for (const section of sections.filter(one => one.issues.length > 0)) {
    lines.push("", section.heading, "");
    for (const issue of SEVERITIES.flatMap(severity =>
      section.issues.filter(one => (one.severity ?? "medium") === severity))) {
      const where = about(issue);
      lines.push(`${number}. (${SEVERITY_NAMES[issue.severity ?? "medium"]}) ${leadIn(where)}${issue.text}`);
      if (issue.passage?.quote) {
        lines.push(`   The protocol says: "${issue.passage.quote}"`);
      }
      number++;
    }
  }
  lines.push("", "Sincerely,", "", reviewer, `On behalf of the ${group} review`);
  return lines.join("\n");
}

// The note that goes with an approval when the reviewer raised suggestions only: the approval stands, and the
// suggestions are there to take up or not
export function approvalLetter({ title, identifier, pi, about, issues, reviewer, group }: LetterInput): string {
  const date = new Date().toLocaleDateString("en-CA", { year: "numeric", month: "long", day: "numeric" });
  return [
    date, "", `To ${pi}`, "", `Re: ${title} (${identifier})`, "",
    `Thank you for your submission. The ${group} review approves it. It also has a few suggestions you may want to `
      + "take up. They are not conditions of the approval.",
    "",
    ...issues.map((issue, index) => `${index + 1}. ${leadIn(about(issue))}${issue.text}`),
    "", "Sincerely,", "", reviewer, `On behalf of the ${group} review`,
  ].join("\n");
}
