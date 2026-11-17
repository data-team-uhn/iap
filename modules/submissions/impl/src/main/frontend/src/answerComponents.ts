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

import type { ReactElement, ReactNode } from "react";

import { type Candidate, createCandidateRegistry } from "@iap/frontend-commons/candidateRegistry";

import type { FormQuestion } from "./submissionForm";

// Which component draws the answer to each question

export interface AnswerComponentProps {
  question: FormQuestion;
  // The answer as it stands: the saved values, or what is being typed
  values: string[];
  disabled: boolean;
  // Called as the answer changes, before it is finished, so that the input shows what was typed
  onChange: (values: string[]) => void;
  // Called with the values to save when the answer is finished. Each component decides when that is
  // for its own kind of input, such as when a field is left or an option picked.
  onAnswered: (values: string[]) => void;
  // Shown beside the answer control, laid out by AnswerRow
  aside?: ReactNode;
  // Whether the answer shown is a suggestion nobody has taken yet. The component frames it.
  suggested?: boolean;
  // Shown in the top right corner of the answer's input box, such as the offer of a newer reading.
  // A component with no box shows it beside the answer instead.
  corner?: ReactNode;
}

export type AnswerComponent = (props: AnswerComponentProps) => ReactElement | null;

// How well a component suits a question
export type AnswerComponentCandidate = Candidate<FormQuestion, AnswerComponent>;

// The `window` property holding the candidates, shared by every bundle that contributes a question type
export const STORE_KEY = "__iapAnswerComponents";

const registry = createCandidateRegistry<FormQuestion, AnswerComponent>(STORE_KEY);

// Offers a component for the questions it recognizes
export const registerAnswerComponent = registry.register;

export const clearAnswerComponents = registry.clear;

/**
 * The registered component that fits this question best, or null when none does.
 *
 * There is no default component. An input not meant for the question would store a value the schema
 * does not accept.
 */
export const getAnswerComponent = registry.get;
