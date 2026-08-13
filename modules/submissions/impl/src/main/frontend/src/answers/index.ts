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

import { registerAnswerComponent } from "../answerComponents";
import { booleanAnswerCandidate } from "./BooleanAnswer";
import { choiceAnswerCandidate } from "./ChoiceAnswer";
import { dateAnswerCandidate } from "./DateAnswer";
import { fileAnswerCandidate } from "./FileAnswer";
import { numberAnswerCandidate } from "./NumberAnswer";
import { textAnswerCandidate } from "./TextAnswer";

/**
 * Registers the answer components that ship with this module.
 *
 * An explicit call rather than a side effect of importing each component, so that what is registered
 * does not depend on which module something happened to import first, and so that a test can start
 * from a known-empty registry. Calling it more than once is harmless: the registry itself ignores a
 * candidate it already holds.
 */
export function registerBuiltinAnswerComponents(): void {
  [
    choiceAnswerCandidate,
    booleanAnswerCandidate,
    dateAnswerCandidate,
    numberAnswerCandidate,
    fileAnswerCandidate,
    textAnswerCandidate,
  ].forEach(registerAnswerComponent);
}
