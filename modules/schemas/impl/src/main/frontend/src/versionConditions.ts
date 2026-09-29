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

import { createContext, useContext } from "react";

import type { OperandSource } from "@iap/conditions/conditionModel";

import { schemaSources } from "./conditionModel";
import { indexQuestions, type QuestionIndex } from "./schemaVersionTreeModel";

// What the conditions of a version's parts are read and built with, made once for the version: its questions, every
// source its conditions may use, and those an editor offers
export interface VersionConditions {
  index: QuestionIndex;
  sources: OperandSource[];
  offered: OperandSource[];
}

const NO_QUESTIONS = indexQuestions({});

export const VersionConditionsContext = createContext<VersionConditions>({
  index: NO_QUESTIONS,
  sources: schemaSources(NO_QUESTIONS),
  offered: [],
});

export const useVersionConditions = (): VersionConditions => useContext(VersionConditionsContext);
