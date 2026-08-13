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

import type { FormQuestion } from "../submissionForm";

/**
 * What to call a question on screen. Each answer component labels its own input, because what
 * carries a label differs by kind — a text field, a checkbox, a group of radio buttons — but they
 * all name the question the same way: its text, falling back to its name when a schema left the
 * text out, which is a schema worth noticing rather than a field worth leaving blank.
 */
export function questionLabel(question: FormQuestion): string {
  return question.text || question.name;
}
