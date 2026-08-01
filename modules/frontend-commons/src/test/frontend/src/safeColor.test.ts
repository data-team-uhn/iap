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

import { safeCssColor } from "@iap/frontend-commons/safeColor";

describe("safeCssColor", () => {
  it("passes through hex, rgb() and hsl() colors", () => {
    expect(safeCssColor("#1976d2")).toBe("#1976d2");
    expect(safeCssColor("#FFF")).toBe("#FFF");
    expect(safeCssColor("#1976d2cc")).toBe("#1976d2cc");
    expect(safeCssColor("rgb(25, 118, 210)")).toBe("rgb(25, 118, 210)");
    expect(safeCssColor("rgba(25, 118, 210, 0.5)")).toBe("rgba(25, 118, 210, 0.5)");
    expect(safeCssColor("hsl(210 79% 46%)")).toBe("hsl(210 79% 46%)");
  });

  it("rejects anything else, including CSS smuggling attempts", () => {
    expect(safeCssColor(undefined)).toBeUndefined();
    expect(safeCssColor("")).toBeUndefined();
    expect(safeCssColor("not-a-color")).toBeUndefined();
    // Named colors are deliberately out: the whitelist stays small and unambiguous
    expect(safeCssColor("tomato")).toBeUndefined();
    expect(safeCssColor("#fff;background:url(https://evil.example/x)")).toBeUndefined();
    expect(safeCssColor("url(https://evil.example/x)")).toBeUndefined();
  });
});
