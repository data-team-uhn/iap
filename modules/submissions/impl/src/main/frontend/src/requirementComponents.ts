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
import type { FormQuestion, Requirement } from "./submissionForm";

// Which component draws each kind of requirement. A module adds a kind by registering a candidate
// for it, and the editor draws that kind without naming it.

// How one answer's save is going, by the path of the question it answers. Held by the editor, which
// is what saves, and read by a requirement that draws questions.
export interface FieldState {
  state: SaveState;
  error?: string;
}

export interface RequirementComponentProps {
  // The path of the submission, for a kind that posts to it
  path: string;
  requirement: Requirement;
  // Whether the request can no longer be changed; a kind still shows what it holds
  disabled: boolean;
  // How each answer's save is going, by question path, for a kind that draws questions
  states: Record<string, FieldState | undefined>;
  // An answer was finished, for a kind that draws questions
  onAnswered: (question: FormQuestion, values: string[]) => void;
  // Called after a kind changed the submission, so the editor reads the form again
  onChanged: () => void;
}

export type RequirementComponent = (props: RequirementComponentProps) => ReactElement | null;

/**
 * How well a component suits a requirement: the component, and a confidence between 0 and 100. The
 * highest confidence wins. Returning null means "not mine".
 */
export type RequirementComponentCandidate = (requirement: Requirement) => [RequirementComponent, number] | null;

// The property name the registry hides under on `window`. A requirement kind may be contributed by a
// bundle built separately, whose copy of this module would otherwise be a second, private registry.
export const STORE_KEY = "__iapRequirementComponents";

const candidates = (): RequirementComponentCandidate[] => {
  const holder = window as unknown as Record<string, RequirementComponentCandidate[] | undefined>;
  holder[STORE_KEY] ??= [];
  return holder[STORE_KEY];
};

/**
 * Offers a component for the requirements it recognizes. Registering the same candidate again does
 * nothing.
 */
export function registerRequirementComponent(candidate: RequirementComponentCandidate): void {
  if (!candidates().includes(candidate)) {
    candidates().push(candidate);
  }
}

// Only for tests: registration is otherwise a one-way, load-time act
export function clearRequirementComponents(): void {
  candidates().length = 0;
}

/**
 * The registered component that fits this requirement best, or null when nothing does.
 */
export function getRequirementComponent(requirement: Requirement): RequirementComponent | null {
  let best: RequirementComponent | null = null;
  let bestConfidence = -1;
  for (const candidate of candidates()) {
    const offer = candidate(requirement);
    // Strictly greater, so a tie goes to the first registered
    if (offer && offer[1] > bestConfidence) {
      [ best, bestConfidence ] = offer;
    }
  }
  return best;
}
