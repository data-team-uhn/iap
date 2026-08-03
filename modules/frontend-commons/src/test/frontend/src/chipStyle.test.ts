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

import { createTheme } from "@mui/material/styles";

import { chipStyle } from "@iap/frontend-commons/chipStyle";

// The derived values are plain strings, so the color-mix()/light-dark() recipes jsdom cannot
// compute are still assertable exactly — which is why this pure function carries the styling
// rules, and the chip components' own tests only check the wiring.
const plain = createTheme();
const withVars = createTheme({ cssVariables: true });

// The clamp bounds are unitless on purpose (OKLCH's l channel is a 0-1 number; a
// number/percentage mix makes the color invalid and browsers drop the whole declaration),
// and perceptual on purpose (an HSL-lightness-clamped yellow still reads blazing bright).
const READABLE_TEXT =
  "light-dark(oklch(from #55408f clamp(0.3, l, 0.5) c h), oklch(from #55408f clamp(0.65, l, 0.85) c h))";

describe("chipStyle", () => {
  it("derives the soft look from the one color, adapting to the active scheme", () => {
    const paper = withVars.vars?.palette.background.paper ?? "";
    // Tinting over the paper CSS variable is what makes the background follow the scheme
    expect(paper).toContain("var(");
    const background = `color-mix(in srgb, #55408f 10%, ${paper})`;
    expect(chipStyle(withVars, "#55408f")).toEqual({
      backgroundColor: background,
      color: READABLE_TEXT,
      border: `1px solid color-mix(in srgb, ${READABLE_TEXT} 22%, ${background})`,
      transition: "none",
    });
  });

  it("falls back to the static paper color on a theme without CSS variables", () => {
    // Component tests use such themes; the real app theme always has the variables
    expect(chipStyle(plain, "#55408f")?.backgroundColor)
      .toBe(`color-mix(in srgb, #55408f 10%, ${plain.palette.background.paper})`);
  });

  it("keeps the classic loud look for the filled variant", () => {
    expect(chipStyle(plain, "#673ab7", "filled")).toEqual({
      backgroundColor: "#673ab7",
      color: plain.palette.getContrastText("#673ab7"),
      transition: "none",
    });
  });

  it("draws only readable text and a matching border for the outlined variant", () => {
    expect(chipStyle(plain, "#55408f", "outlined")).toEqual({
      backgroundColor: "transparent",
      color: READABLE_TEXT,
      border: `1px solid ${READABLE_TEXT}`,
      transition: "none",
    });
  });

  it("treats any unrecognized variant as soft", () => {
    expect(chipStyle(plain, "#55408f", "sparkly")).toEqual(chipStyle(plain, "#55408f"));
  });

  it("declines to style anything without a usable color", () => {
    expect(chipStyle(plain)).toBeUndefined();
    expect(chipStyle(plain, "not-a-color")).toBeUndefined();
    // The color is interpolated into generated CSS, so a smuggling attempt must not pass
    expect(chipStyle(plain, "#fff;background:url(https://evil.example/x)")).toBeUndefined();
    expect(chipStyle(plain, "#fff;background:url(https://evil.example/x)", "filled")).toBeUndefined();
  });
});
