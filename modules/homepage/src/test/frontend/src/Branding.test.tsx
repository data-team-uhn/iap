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
import { MemoryRouter } from "react-router";

import { appTheme } from "@iap/frontend-commons/appTheme";
import Branding from "@iap/homepage/Branding";

// The app name and logo come from the page metadata; provide them the way the server does
beforeAll(() => {
  const metas = { title: "Test App", logoLight: "/light.svg", logoDark: "/dark.svg" };
  for (const [name, content] of Object.entries(metas)) {
    const meta = document.createElement("meta");
    meta.name = name;
    meta.content = content;
    document.head.append(meta);
  }
});

const renderBranding = () => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <MemoryRouter>
      <Branding />
    </MemoryRouter>
  </ThemeProvider>
);

describe("Branding", () => {
  it("displays the wordmark for the active scheme, named by the application name", async () => {
    renderBranding();

    const wordmark = await screen.findByAltText("Test App");
    expect(wordmark).toHaveAttribute("src", "/light.svg");
  });

  it("links back to the homepage", async () => {
    renderBranding();

    const link = (await screen.findByAltText("Test App")).closest("a");
    expect(link).toHaveAttribute("href", "/");
  });
});
