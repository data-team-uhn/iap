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

import { comparable, disagrees } from "./FormReview";

import type { FormQuestion } from "../submissionForm";
import type { ReviewData, ReviewNote } from "./reviewData";

// How far the submitter's answer may be from what the AI read and still be linked to it, in percent, and how sure the
// AI must be for an answer the protocol says otherwise to show as a contradiction
const KEPT = 20;
const SURE = 0.7;

// PRETOTYPE. What the AI read in the protocol for an answer, as a reviewer is shown it: the passage linked to the
// answer, where the submitter kept what was read, or a contradiction, where the protocol clearly says otherwise; or
// nothing, where neither can be told. Answers in words are linked only when kept, since rewording them says nothing
// of the protocol. How sure the AI was is not shown.
function asSource(question: FormQuestion | undefined, note: ReviewNote): ReviewNote | undefined {
  if (!question || !note.extracted) {
    return undefined;
  }
  if (comparable(question)) {
    if (!disagrees(question, note.extracted)) {
      return note;
    }
    return (note.confidence ?? 0) >= SURE ? { ...note, kind: "contradiction" } : undefined;
  }
  return note.distance !== undefined && note.distance <= KEPT ? note : undefined;
}

// The review data with the AI's readings of the answers turned into linked answers and contradictions
export function withSources(data: ReviewData, questions: FormQuestion[]): ReviewData {
  const asked = new Map(questions.map(question => [ question.path, question ]));
  return { ...data, notes: data.notes.flatMap(note => {
    if (note.kind !== "evidence") {
      return [ note ];
    }
    const shown = asSource(note.question ? asked.get(note.question) : undefined, note);
    return shown ? [ shown ] : [];
  }) };
}
