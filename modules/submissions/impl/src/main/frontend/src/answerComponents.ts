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

import { type Candidate, createCandidateRegistry } from "@iap/frontend-commons/candidateRegistry";

import type { FormQuestion } from "./submissionForm";

// Which component displays each question type

export interface AnswerComponentProps {
  question: FormQuestion;
  // The answer as it currently stands, starting with the saved values, updated when somebody starts typing
  values: string[];
  disabled: boolean;
  // While the answer is being composed, so that what was typed is what is shown
  onChange: (values: string[]) => void;
  // When the answer is finished: a field left, a box ticked, an option picked. This is what gets
  // saved, so a component decides for its own kind of input when an answer is done being given
  onAnswered: (values: string[]) => void;
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
 * The registered component that fits this question best, or null when nothing does.
 *
 * Null rather than a fallback to some default. Typing an answer into an input never meant for it
 * produces a value the schema does not accept, and nobody notices until a condition stops matching.
 */
export const getAnswerComponent = registry.get;
