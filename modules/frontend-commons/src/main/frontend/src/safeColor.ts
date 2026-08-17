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

// The color notations repository-editable content (e.g. tag definitions) may use: hex, rgb()
// or hsl(). Being strict here both keeps palette helpers like getContrastText from choking on
// garbage, and keeps a malicious value from smuggling arbitrary CSS into generated styles.
const SAFE_COLOR = /^(#([0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})|(rgb|hsl)a?\([\d\s.,%/]*\))$/i;

// The given color when it is safe to place into styles, undefined for anything else —
// including named colors, which the shipped content avoids.
export function safeCssColor(color: string | undefined): string | undefined {
  return color && SAFE_COLOR.test(color) ? color : undefined;
}
