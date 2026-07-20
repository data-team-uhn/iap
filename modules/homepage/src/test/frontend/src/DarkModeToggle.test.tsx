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
import { fireEvent, render, screen } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import DarkModeToggle from "@iap/homepage/DarkModeToggle";

// jsdom has no matchMedia, which MUI's color scheme system consults for the system preference;
// stub it out as "no preference" so the toggle can run outside a real browser.
beforeAll(() => {
  window.matchMedia = window.matchMedia ?? ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }) as unknown as MediaQueryList);
});

// MUI persists the explicitly chosen mode; clear it so tests don't leak into each other.
// (window.localStorage, not the bare global: outside a --localstorage-file'd Node the global
// resolves to Node's own, undefined one instead of jsdom's.)
afterEach(() => window.localStorage?.clear());

const renderToggle = () => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <DarkModeToggle />
  </ThemeProvider>
);

describe("DarkModeToggle", () => {
  it("offers to switch to dark mode when the light scheme is active", async () => {
    renderToggle();

    expect(await screen.findByRole("button", { name: "Switch to dark mode" })).toBeInTheDocument();
  });

  it("switches to the dark scheme when clicked, then offers to switch back", async () => {
    renderToggle();

    fireEvent.click(await screen.findByRole("button", { name: "Switch to dark mode" }));

    expect(await screen.findByRole("button", { name: "Switch to light mode" })).toBeInTheDocument();
  });
});
