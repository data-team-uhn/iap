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

// jsdom has no matchMedia, which MUI asks for the system's colour scheme. This one matches no
// query.
beforeAll(() => {
  vi.stubGlobal("matchMedia", window.matchMedia ?? ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })));
});

// MUI keeps the chosen mode in localStorage, which would carry it into the next test
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

  it("resolves the system scheme when the deployment defaults to it", async () => {
    render(
      <ThemeProvider theme={appTheme} defaultMode="system">
        <DarkModeToggle />
      </ThemeProvider>
    );

    // The stubbed matchMedia does not match a dark preference, so the system scheme is light
    expect(await screen.findByRole("button", { name: "Switch to dark mode" })).toBeInTheDocument();
  });
});
