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

// The first letters of a name's first and last words, in upper case: what an avatar shows for a
// person. Empty for a blank name.
export const initialsOf = (name: string): string => {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) {
    return "";
  }
  return (words[0][0] + (words.length > 1 ? words[words.length - 1][0] : "")).toUpperCase();
};

// Joins words into a list for a sentence: "a, b and c"
export const listed = (words: string[]): string => (words.length < 2 ? words.join("")
  : `${words.slice(0, -1).join(", ")} and ${words.slice(-1).join("")}`);

// A noun in the number a count calls for, such as "field" for 1 and "fields" otherwise. The plural takes
// a plain "s".
export const nounFor = (value: number, noun: string): string => (value === 1 ? noun : `${noun}s`);

// A count and its noun, such as "1 field" or "3 fields"
export const countOf = (value: number, noun: string): string => `${String(value)} ${nounFor(value, noun)}`;
