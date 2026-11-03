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

export const MEGABYTE = 1024 * 1024;

/** What a look inside a file found: nothing wrong, or what is wrong, for a person to read. */
export type ContentCheck = { valid: true } | { valid: false; error: string };

// A size for a person to read: KB under a megabyte, two decimals at most. A file's size rounds up and
// a limit's down, so a file over a limit never shows the same number as the limit.
export function formatSize(bytes: number, rounding: (value: number) => number): string {
  const [ unit, name ] = bytes < MEGABYTE ? [ 1024, "KB" ] : [ MEGABYTE, "MB" ];
  return `${rounding(bytes / unit * 100) / 100} ${name}`;
}
