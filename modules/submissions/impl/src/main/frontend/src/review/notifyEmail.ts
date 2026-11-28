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
import { NATURE_NAMES, type Nature, type ReviewNote } from "./reviewData";

// PRETOTYPE. What a reviewer group that writes no letter sends the study team when it has questions or changes for
// them: an email from the platform, its subject naming the review, listing every open item in full, the required
// changes first, and asking the team to answer each one where the submission is reviewed.
export interface IssuesEmail {
  from: string;
  to: string;
  subject: string;
  // Under their headings, as the email lists them
  sections: { heading: string; points: string[] }[];
  opening: string;
  closing: string;
}

export interface IssuesEmailInput {
  // The review, as its group calls it, such as "Digital review"
  review: string;
  identifier: string;
  title: string;
  pi: string;
  issues: ReviewNote[];
  about: (note: ReviewNote) => string;
}

const ORDER: Nature[] = [ "required-change", "question", "suggestion" ];
const HEADINGS: Record<Nature, string> = { "required-change": "Required changes", question: "Questions",
  suggestion: "Suggestions" };

function counted(count: number, nature: Nature): string {
  const name = NATURE_NAMES[nature].toLowerCase();
  return count === 1 ? `1 ${name}` : `${count} ${name}s`;
}

export function issuesEmail({ review, identifier, title, pi, issues, about }: IssuesEmailInput): IssuesEmail {
  const byNature = ORDER.map(nature => ({ nature, issues: issues.filter(issue =>
    (issue.nature ?? "required-change") === nature) })).filter(group => group.issues.length > 0);
  const what = byNature.map(group => counted(group.issues.length, group.nature)).join(" and ") || "nothing";
  return {
    from: "Research review notifications",
    to: `${pi} and the study team`,
    subject: `${review}: ${what} on ${identifier}`,
    opening: `The ${review} of “${title}” (${identifier}) has ${what} for you. Please answer each one where your `
      + "submission is reviewed, changing the submission there where it applies. The review is paused until you send "
      + "it back.",
    sections: byNature.map(group => ({
      heading: HEADINGS[group.nature],
      points: group.issues.map((issue, index) => {
        const where = about(issue);
        const severity = group.nature === "required-change" && issue.severity ? `(${issue.severity}) ` : "";
        return `${index + 1}. ${severity}${where ? `${where}: ` : ""}${issue.text}`;
      }),
    })),
    closing: `Sent on behalf of the ${review}.`,
  };
}

// The email as text, as it is sent and kept with the decision
export function emailText(email: IssuesEmail): string {
  return [
    `Subject: ${email.subject}`,
    "",
    `Dear ${email.to},`,
    "",
    email.opening,
    ...email.sections.flatMap(section => [ "", section.heading, ...section.points ]),
    "",
    email.closing,
  ].join("\n");
}
