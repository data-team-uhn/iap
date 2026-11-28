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

import { NATURE_NAMES, SEVERITIES, type ReviewNote } from "./reviewData";
import { UHN_LOGO } from "./uhnLogo";

// PRETOTYPE. A reviewer's letter to the study team, drawn from the issues they raised, which stay what the letter
// says: it is not edited itself. An approval naming a template, as the REB's does, gets that template's wording and
// sections; any other, a plain letter going risk by risk.

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
  // The template the reviewer's approval names, and the template's section an issue goes under, if it says
  template?: string;
  sectionOf?: (issue: ReviewNote) => string | undefined;
}

// A point of the letter: an issue, numbered through the letter
export interface LetterPoint {
  id: string;
  number: number;
  // What it is, said before it: a required change's severity, or "Suggestion" or "Question"
  label: string;
  about: string;
  text: string;
  quote?: string;
  // PRETOTYPE: the policy it rests on
  citation?: string;
}

export interface LetterSection {
  heading: string;
  points: LetterPoint[];
}

// A letter as its template lays it out, every word of it, for the page showing it and the text sent
export interface DraftedLetter {
  // The sender's logo, its letterhead and the line at the foot of each page, when the template has them
  logo?: string;
  letterhead?: string[];
  footer?: string;
  date: string;
  salutation: string;
  re: string[];
  opening: string[];
  sections: LetterSection[];
  closing: string[];
  signature: string[];
  // Who the letter is sent for, by role, under the signature
  signatories?: { heading: string; lines: string[] }[];
}

const SEVERITY_NAMES: Record<string, string> = { critical: "Critical", major: "Major", medium: "Medium", minor: "Minor" };

// Under a heading, the required changes, most serious first, then the questions, then the suggestions
const ORDER = [ ...SEVERITIES, "question", "suggestion" ];
const placeOf = (issue: ReviewNote) => (issue.nature === "question" || issue.nature === "suggestion" ? issue.nature
  : issue.severity ?? "medium");

// What an issue is about, leading into it: a question already ends a sentence
export function leadIn(where: string): string {
  return !where ? "" : /[?.!]$/.test(where) ? `${where} ` : `${where}. `;
}

// The template of the University Health Network's Research Ethics Board, without its references to the system it
// was made for: its sections, in its order, the last of the general ones taking what no other does
export const UHN_REB = "uhnReb";
export const UHN_REB_SECTIONS = [ "Study Design", "Recruitment and Consenting Process", "Privacy and Confidentiality",
  "Other Issues", "Questionnaire(s)", "Consent Form - Main", "Consent Form - Optional" ];
const UHN_REB_OTHER = "Other Issues";
const UHN_REB_CONSENT = [ "Consent Form - Main", "Consent Form - Optional" ];
const CO_CHAIR = "Co-Chair, University Health Network Research Ethics Board";
const VICE_CHAIR = "Vice-Chair, University Health Network Research Ethics Board";

// The issues as points, section by section in the order given, the most serious first within each, numbered through
function pointsOf(sections: { heading: string; issues: ReviewNote[] }[], about: (note: ReviewNote) => string) {
  let number = 1;
  return sections.map(section => ({
    heading: section.heading,
    points: ORDER.flatMap(place => section.issues.filter(one => placeOf(one) === place))
      .map(issue => ({ id: issue.id, number: number++,
        label: issue.nature === "question" || issue.nature === "suggestion" ? NATURE_NAMES[issue.nature]
          : SEVERITY_NAMES[issue.severity ?? "medium"],
        about: about(issue), text: issue.text, quote: issue.passage?.quote, citation: issue.citation })),
  })).filter(section => section.points.length > 0);
}

export function draftLetter(input: LetterInput): DraftedLetter {
  const { title, identifier, pi, about, issues, risks, reviewer, group } = input;
  if (input.template === UHN_REB) {
    const sectionOf = (issue: ReviewNote) => {
      const named = input.sectionOf?.(issue);
      return named && UHN_REB_SECTIONS.includes(named) ? named : UHN_REB_OTHER;
    };
    const sections = pointsOf(UHN_REB_SECTIONS.map(heading => ({ heading,
      issues: issues.filter(issue => sectionOf(issue) === heading) })), about);
    return {
      logo: UHN_LOGO,
      letterhead: [ "University Health Network", "Research Ethics Board", "4th Floor, 700 University Avenue",
        "Toronto, Ontario, M5G 1Z5", "Phone: (416) 581-7849" ],
      footer: "The UHN Research Ethics Board operates in compliance with the Tri-Council Policy Statement, ICH/GCP "
        + "Guidelines and Part C, Division 5 of the Food and Drug Regulations of Health Canada.",
      date: new Date().toLocaleDateString("en-GB", { day: "numeric", month: "long", year: "numeric" }),
      salutation: `Dear ${pi},`,
      re: [ `Re: Submission ID: ${identifier}`, title ],
      opening: [
        "Thank you for this submission to the University Health Network Research Ethics Board. The REB has reviewed "
          + "this proposal via delegated type of review. Please find below the queries and comments that were raised "
          + "by this review.",
        "To facilitate the review and approval process, please ensure that each query below is addressed in full "
          + "within the response letter. Submissions that do not include a complete written response to all queries "
          + "may not be accepted for review.",
      ],
      sections,
      closing: [
        ...sections.some(section => UHN_REB_CONSENT.includes(section.heading))
          ? [ "Reminder as you revise: Consent forms should be written at an appropriate reading level using laymen's "
            + "terms and organized in a fashion that ensures easy readability and flow. Please avoid technical "
            + "words/jargon, if these absolutely must be used, a simple definition should be included beside the "
            + "terminology in brackets." ] : [],
        "Please respond online, attaching the response letter and uploading other required/revised documents to the "
          + "submission.",
        "Please note that the version date must be updated each time a revised document is submitted to the REB.",
        "Please note that once the response is reviewed, further questions generated by your response may be asked. "
          + "Once all queries and comments concerning this proposal have been addressed to the REBs satisfaction, you "
          + "will be informed.",
        "Please note that this file will be permanently closed if the review of this submission is not complete "
          + "within a year from the date of this letter.",
        "If you have any questions regarding this letter please contact me or the Co-Chair at (416) 581-7849.",
        "The REB looks forward to receiving your response.",
      ],
      signature: [ "Sincerely,", reviewer, "Reviewer, Research Ethics Board" ],
      signatories: [
        { heading: "Board A/B", lines: [ "Morris Sherman, MB BCh PhD, FRCP(C), FAASLD",
          "Professor Emeritus, University of Toronto,", CO_CHAIR ] },
        { heading: "Board C", lines: [ "Eric X. Chen, MD, PhD, FRCPC", CO_CHAIR ] },
        { heading: "For Minimal Risk KITE Studies", lines: [ "Anthony Burns, MD, MSc", CO_CHAIR ] },
        { heading: "Vice-Chairs, Board A", lines: [ "Connie Marras, MD, PhD, FRCPC", VICE_CHAIR ] },
        { heading: "Board B", lines: [ "Jean Wang, MD, PhD, FRCPC", VICE_CHAIR ] },
        { heading: "Board C", lines: [ "Albiruni Razak, MB MRCPI",
          "Vice-Chair, University Health Network Oncology Research Ethics Board" ] },
      ],
    };
  }
  const known = new Set(risks.map(risk => risk.path));
  return {
    date: new Date().toLocaleDateString("en-CA", { year: "numeric", month: "long", day: "numeric" }),
    salutation: `Dear ${pi},`,
    re: [ `Re: ${title} (${identifier})` ],
    opening: [ `Thank you for your submission. The ${group} review has looked at it and asks that you respond to the `
      + "points below. Please answer each one, revise the submission where it applies, and send it back." ],
    sections: pointsOf([
      ...risks.map(risk => ({ heading: risk.label, issues: issues.filter(issue => issue.topicPath === risk.path) })),
      { heading: "Other points", issues: issues.filter(issue => !issue.topicPath || !known.has(issue.topicPath)) },
    ], about),
    closing: [],
    signature: [ "Sincerely,", reviewer, `On behalf of the ${group} review` ],
  };
}

// A point as the letter says it
export const pointText = (point: LetterPoint) => `${point.number}. (${point.label}) ${leadIn(point.about)}${point.text}`;

// The letter as text, as it is sent and kept with the decision
export function letterText(letter: DraftedLetter): string {
  const blocks = [
    ...letter.letterhead ? [ letter.letterhead.join("\n") ] : [],
    letter.date, letter.salutation, letter.re.join("\n"), ...letter.opening,
    ...letter.sections.map(section => [ section.heading, ...section.points.map(point =>
      [ pointText(point), ...point.quote ? [ `   The protocol says: "${point.quote}"` ] : [],
        ...point.citation ? [ `   Reference: ${point.citation}` ] : [] ].join("\n")) ].join("\n\n")),
    ...letter.closing, letter.signature.join("\n"),
    ...(letter.signatories ?? []).map(one => [ one.heading, ...one.lines ].join("\n")),
    ...letter.footer ? [ letter.footer ] : [],
  ];
  return blocks.join("\n\n");
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
