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

import type { FormQuestion } from "./submissionForm";

// Which component types a question in, decided by the question itself rather than by a list of
// dataTypes kept somewhere central. A new kind of question is added by registering a candidate for
// it, and nothing that already exists has to learn about it.

export interface AnswerComponentProps {
  question: FormQuestion;
  // The answer as it currently stands: the saved one until somebody starts typing
  values: string[];
  disabled: boolean;
  // While the answer is being composed, so that what was typed is what is shown
  onChange: (values: string[]) => void;
  // When the answer is finished — a field left, a box ticked, an option picked. This is what gets
  // saved, so a component decides for its own kind of input when an answer is done being given
  onAnswered: (values: string[]) => void;
}

export type AnswerComponent = (props: AnswerComponentProps) => ReactElement | null;

/**
 * How well a component suits a question: the component, and a confidence between 0 and 100. The
 * highest confidence wins, so a candidate answering a narrow question — one dataType, or one
 * dataType with options — outbids a general one without either having to know about the other.
 * Returning null means "not mine".
 */
export type AnswerComponentCandidate = (question: FormQuestion) => [AnswerComponent, number] | null;

const candidates: AnswerComponentCandidate[] = [];

/**
 * Offers a component for the questions it recognizes.
 *
 * Registering the same candidate again does nothing, so that whoever registers need not track
 * whether they already have: the same module evaluated twice, or two modules each making sure their
 * own components are present, leave one registration and not several.
 */
export function registerAnswerComponent(candidate: AnswerComponentCandidate): void {
  if (!candidates.includes(candidate)) {
    candidates.push(candidate);
  }
}

// Only for tests: registration is otherwise a one-way, load-time act
export function clearAnswerComponents(): void {
  candidates.length = 0;
}

/**
 * The registered component that fits this question best, or null when nothing does.
 *
 * Answering with null rather than falling back to some default is deliberate: a question whose kind
 * this deployment has no component for should say so, because typing an answer into an input that
 * was never meant for it produces a value the schema does not accept and nobody notices until a
 * condition somewhere quietly stops matching.
 */
export function getAnswerComponent(question: FormQuestion): AnswerComponent | null {
  let best: AnswerComponent | null = null;
  let bestConfidence = -1;
  for (const candidate of candidates) {
    const offer = candidate(question);
    // Ties go to the first registered, which makes the outcome depend on load order and not on the
    // order the candidates happen to be visited in
    if (offer && offer[1] > bestConfidence) {
      [ best, bestConfidence ] = offer;
    }
  }
  return best;
}
