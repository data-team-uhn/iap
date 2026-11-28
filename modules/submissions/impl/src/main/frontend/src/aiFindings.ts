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
// PRETOTYPE. The AI pre-review's findings as the study team sees them before sending: what each says, what it is
// about, and what the team did with it, a response or a change made since the AI read the submission.

import { type SerializedNode, childrenOfPrimaryType, isNode, textOf } from "@iap/frontend-commons/serializedNode";

// What a finding is about: a question, by its path relative to the schema version, or a document
export interface FindingSubject {
  label: string;
  question?: string;
}

export interface AiFinding {
  // The AI comment, by path
  id: string;
  // The risk it was raised under
  topic: string;
  text: string;
  suggestion?: string;
  severity?: string;
  subjects: FindingSubject[];
  // Why the team leaves it as it is, when they said
  response?: string;
  // Whether an answer or a document it is about changed after the AI read the submission
  changed: boolean;
}

export interface AiPreReview {
  summary?: string;
  findings: AiFinding[];
}

const SEVERITIES = [ "critical", "major", "medium", "minor", "suggestion" ];

// When something was written, as the repository recorded it
const writtenAt = (node: SerializedNode, key = "jcr:created"): number => Date.parse(textOf(node, key) ?? "") || 0;

// A question's path relative to the schema version, from its full path
const relative = (question: SerializedNode, version: string): string | undefined => {
  const path = textOf(question, "@path");
  return path?.startsWith(`${version}/`) ? path.slice(version.length + 1) : undefined;
};

// The AI pre-review of a submission read with the `deep` serialization; undefined when the AI has not read it
export function aiPreReview(submission: SerializedNode): AiPreReview | undefined {
  const screening = childrenOfPrimaryType(submission, "sub:Screening").at(0);
  if (!screening) {
    return undefined;
  }
  const version = isNode(submission.schemaVersion) ? textOf(submission.schemaVersion, "@path") ?? "" : "";
  const read = writtenAt(screening);
  const answers = childrenOfPrimaryType(submission, "sub:Answer");
  // An answer to the question, given or changed since the AI read the submission
  const answeredSince = (question: string) => answers.some(answer => isNode(answer.question)
    && relative(answer.question, version) === question && writtenAt(answer, "jcr:lastModified") > read);
  const findings = childrenOfPrimaryType(screening, "sub:Assessment").flatMap(assessment => {
    const topic = isNode(assessment.topic) ? textOf(assessment.topic, "text") ?? "" : "";
    return childrenOfPrimaryType(assessment, "sub:Comment").map(comment => {
      const subjects = (Array.isArray(comment.subjects) ? comment.subjects : []).filter(isNode);
      const about = subjects.map(subject => {
        if (subject["jcr:primaryType"] === "sub:Answer" && isNode(subject.question)) {
          return { label: textOf(subject.question, "text") ?? "", question: relative(subject.question, version) };
        }
        if (subject["jcr:primaryType"] === "sch:Question") {
          return { label: textOf(subject, "text") ?? "", question: relative(subject, version) };
        }
        // A document, by the requirement it fulfills
        const fulfills = isNode(subject.fulfills) ? textOf(subject.fulfills, "label") : undefined;
        return { label: fulfills ?? textOf(subject, "title") ?? textOf(subject, "label") ?? "" };
      });
      const documentChanged = subjects.some(subject => subject["jcr:primaryType"] === "sub:Document"
        && writtenAt(subject, "jcr:lastModified") > read);
      const response = childrenOfPrimaryType(comment, "sub:Reply").find(reply => reply["@name"] === "response");
      return {
        id: textOf(comment, "@path") ?? "",
        topic,
        text: textOf(comment, "text") ?? "",
        suggestion: textOf(comment, "suggestion"),
        severity: (Array.isArray(comment.tags) ? comment.tags.map(String) : []).find(tag => SEVERITIES.includes(tag)),
        subjects: about.filter(subject => subject.label),
        response: response ? textOf(response, "text") : undefined,
        changed: documentChanged || about.some(subject => subject.question && answeredSince(subject.question)),
      };
    });
  });
  return { summary: textOf(screening, "summary"), findings };
}

// How many findings the team changed something for, responded to, or left as they are
export function findingCounts(findings: AiFinding[]): { changed: number; responded: number; left: number } {
  const changed = findings.filter(one => one.changed).length;
  const responded = findings.filter(one => !one.changed && one.response).length;
  return { changed, responded, left: findings.length - changed - responded };
}
