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

// Numbering versions the way the server does (VersionNumbers, in java-utils), whatever is being
// versioned: the next version is numbered one past the largest number a version's node name starts
// with, and labelled after it unless whoever creates it asks for another label. Names are read rather
// than labels, since a label is free text, as likely a year as a number; and the largest is taken
// rather than the count, so that a version discarded from the middle leaves no number for a new one to
// take again.

// A leading whole number, after an optional v: v3, 3.0, 3
const NUMBERED = /^[vV]?(\d{1,9})/;

// The label a new version is offered, given the node names of the versions already there: 3.0 after v2.
export function nextVersionLabel(names: readonly string[]): string {
  const numbers = names.map(name => Number(NUMBERED.exec(name)?.[1] ?? 0));
  return `${Math.max(0, ...numbers) + 1}.0`;
}
