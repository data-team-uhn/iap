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
// The inputs with nothing to type in.
const NOTHING_TO_TYPE = [
  "button", "checkbox", "color", "file", "hidden", "image", "radio", "range", "reset", "submit",
];

// Where text is typed: a text box, or an element made editable. Not a checkbox, a button or another input
// with nothing to type in, nor one hidden from people, such as the input behind a select menu.
export const TEXT_ENTRY = [
  "textarea:not([aria-hidden=\"true\"])",
  "[contenteditable]:not([contenteditable=\"false\"])",
  `input${NOTHING_TO_TYPE.map(type => `:not([type=${type}])`).join("")}:not([aria-hidden="true"])`,
].join(", ");
