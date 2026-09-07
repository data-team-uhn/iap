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

import type { ReactElement } from "react";

import type { SaveState } from "./AnswerField";
import type { FormQuestion, FormRequirement } from "./submissionForm";

// What draws the body of one requirement, decided by the requirement itself rather than by a list of
// kinds kept somewhere central. A new kind of requirement is added by registering a candidate for it,
// and nothing that already exists has to learn about it — which is what lets a kind be declared by a
// module the editor has never heard of, since the server projects it the same way.

// How one answer's save is going, by the path of the question it answers. Held by the editor, which
// is what saves, and read by a requirement that draws questions.
export interface FieldState {
  state: SaveState;
  error?: string;
}

export interface RequirementComponentProps {
  // Where the submission lives, for a kind that has something to post
  path: string;
  requirement: FormRequirement;
  // While the request can no longer be changed, so a kind still shows what it holds
  disabled: boolean;
  // How each answer's save is going, for a kind that draws questions
  states: Record<string, FieldState | undefined>;
  // An answer was finished, for a kind that draws questions
  onAnswered: (question: FormQuestion, values: string[]) => void;
  // The submission itself changed, so the form has to be read again. Not the same as an answer being
  // saved: what the request is still missing is recorded on the submission, and something else reads it
  onChanged: () => void;
}

export type RequirementComponent = (props: RequirementComponentProps) => ReactElement | null;

export type RequirementComponentCandidate =
  (requirement: FormRequirement) => [RequirementComponent, number] | null;

const candidates: RequirementComponentCandidate[] = [];

export function registerRequirementComponent(candidate: RequirementComponentCandidate): void {
  if (!candidates.includes(candidate)) {
    candidates.push(candidate);
  }
}

// Only for tests: registration is otherwise a one-way, load-time act
export function clearRequirementComponents(): void {
  candidates.length = 0;
}

export function getRequirementComponent(requirement: FormRequirement): RequirementComponent | null {
  let best: RequirementComponent | null = null;
  let bestConfidence = -1;
  for (const candidate of candidates) {
    const offer = candidate(requirement);
    // Ties go to the first registered, which makes the outcome depend on load order and not on the
    // order the candidates happen to be visited in
    if (offer && offer[1] > bestConfidence) {
      [ best, bestConfidence ] = offer;
    }
  }
  return best;
}
