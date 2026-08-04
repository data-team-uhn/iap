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

import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import DateWidget from "@iap/test-data/DateWidget";

// Thursday, 16 July 2026 — at noon, so that no timezone the suite happens to run in can pull the
// rendered date onto the day before or after.
const THURSDAY = new Date(2026, 6, 16, 12, 0, 0);

const renderWidget = () => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <DateWidget />
  </ThemeProvider>
);

// The rendered text, read off the container rather than matched by name: what it says is exactly
// what each test is here to determine.
const renderDate = () => renderWidget().container.textContent;

// One piece of the fixture date, formatted the way the ambient locale would. Expectations go
// through Intl rather than through literal English so that they hold under any default locale.
const part = (options: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat(undefined, options).format(THURSDAY);

// The widget reads the clock, so every test pins it first. Only Date is faked — the timers stay
// real, since nothing here waits on one.
describe("DateWidget", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(THURSDAY);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("shows today's date, spelled out in full", () => {
    const rendered = renderDate();

    expect(rendered).toContain(part({ weekday: "long" }));
    expect(rendered).toContain(part({ month: "long" }));
    expect(rendered).toContain(part({ day: "numeric" }));
    expect(rendered).toContain(part({ year: "numeric" }));
  });

  it("spells the date out rather than leaving it in the locale's terse default form", () => {
    expect(renderDate()).not.toBe(THURSDAY.toLocaleDateString());
  });

  it("follows the clock rather than a date fixed at build time", () => {
    const thursday = renderDate();

    vi.setSystemTime(new Date(2027, 0, 1, 12, 0, 0));

    expect(renderDate()).not.toBe(thursday);
  });

  // The dashboard supplies the widget's title, so a second heading inside the frame would both
  // duplicate it and land in the document outline; the date only borrows the h5 type scale.
  it("gives the date heading-sized text without making it a heading", () => {
    const { container } = renderWidget();

    expect(container.querySelector("p")).toHaveClass("MuiTypography-h5");
    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
  });
});
