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

// A size for a person to read: in KB under a megabyte and in MB above, with two decimals at most.
// `rounding` is Math.ceil for a file's size and Math.floor for a limit, so a file over a limit never
// shows the limit's number.
export function formatSize(bytes: number, rounding: (value: number) => number): string {
  const [ unit, name ] = bytes < MEGABYTE ? [ 1024, "KB" ] : [ MEGABYTE, "MB" ];
  return `${rounding(bytes / unit * 100) / 100} ${name}`;
}
