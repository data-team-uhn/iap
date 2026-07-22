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
import Logo from "@iap/frontend-commons/components/Logo";

// Logo reads the deployment branding from <meta> tags in the page head, so each test seeds the
// head and clears the metas afterwards (only the metas — the head also holds the styles emotion
// injected, which must survive between renders).
describe("Logo", () => {
  afterEach(() => {
    document.head.querySelectorAll("meta").forEach(meta => meta.remove());
  });

  const renderLogo = (source?: "app" | "affiliation", mode: "light" | "dark" = "light") => render(
    <ThemeProvider theme={appTheme} defaultMode={mode}>
      <Logo source={source} />
    </ThemeProvider>
  );

  const seedMetas = (metas: Record<string, string>) => {
    for (const [name, content] of Object.entries(metas)) {
      const meta = document.createElement("meta");
      meta.name = name;
      meta.content = content;
      document.head.append(meta);
    }
  };

  it("renders the application logo named after the page title", () => {
    seedMetas({ title: "IAP", logoLight: "/logo.light.png" });

    renderLogo();

    const img = screen.getByRole("img", { name: "IAP" });
    expect(img).toHaveAttribute("src", "/logo.light.png");
  });

  it("picks the variant matching the active colour scheme", () => {
    seedMetas({ title: "IAP", logoLight: "/logo.light.png", logoDark: "/logo.dark.png" });

    renderLogo("app", "dark");

    expect(screen.getByRole("img", { name: "IAP" })).toHaveAttribute("src", "/logo.dark.png");
  });

  it("renders the affiliated institution's logo, named after the institution", () => {
    seedMetas({ affiliationLogoLight: "/affiliation.light.png", affiliationName: "Some Hospital" });

    renderLogo("affiliation");

    expect(screen.getByRole("img", { name: "Some Hospital" })).toHaveAttribute("src", "/affiliation.light.png");
  });

  it("renders nothing when the requested image is not configured", () => {
    seedMetas({ title: "IAP" });

    const { container } = renderLogo("affiliation");

    expect(container).toBeEmptyDOMElement();
  });
});
