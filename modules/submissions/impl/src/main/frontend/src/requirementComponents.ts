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

import type { SaveState } from "./AnswerField";
import type { FormQuestion, Requirement } from "./submissionForm";

// Which component draws each kind of requirement

// How one answer's save is going
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
  // Called when an answer is finished, for a kind that draws questions
  onAnswered: (question: FormQuestion, values: string[]) => void;
  // Called with each save the kind starts, so that whatever waits for the answers to be stored waits
  // for it too
  onSaving: (save: Promise<unknown>) => void;
  // Called after a kind changed the submission, so the editor reads the form again
  onChanged: () => void;
}

export type RequirementComponent = (props: RequirementComponentProps) => ReactElement | null;

// How well a component suits a requirement
export type RequirementComponentCandidate = Candidate<Requirement, RequirementComponent>;

// The `window` property holding the candidates, shared by every bundle that contributes a requirement kind
export const STORE_KEY = "__iapRequirementComponents";

const registry = createCandidateRegistry<Requirement, RequirementComponent>(STORE_KEY);

// Offers a component for the requirements it recognizes
export const registerRequirementComponent = registry.register;

export const clearRequirementComponents = registry.clear;

// The registered component that fits this requirement best, or null when nothing does
export const getRequirementComponent = registry.get;
