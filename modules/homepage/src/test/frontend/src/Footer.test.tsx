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
import Footer from "@iap/homepage/Footer";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(),
}));

const mockedLoadExtensions = vi.mocked(loadExtensions);

// The affiliation branding comes from <meta> tags in the page head, seeded per test and removed
// afterwards (only the metas — the head also holds the styles emotion injected, which must
// survive between renders).
describe("Footer", () => {
  beforeEach(() => {
    mockedLoadExtensions.mockResolvedValue([]);
  });

  afterEach(() => {
    document.head.querySelectorAll("meta").forEach(meta => meta.remove());
  });

  const seedMetas = (metas: Record<string, string>) => {
    for (const [name, content] of Object.entries(metas)) {
      const meta = document.createElement("meta");
      meta.name = name;
      meta.content = content;
      document.head.append(meta);
    }
  };

  const renderFooter = () => render(
    <ThemeProvider theme={appTheme} defaultMode="light">
      <MemoryRouter>
        <Footer />
      </MemoryRouter>
    </ThemeProvider>
  );

  it("credits DATA, linking to the team's site", () => {
    renderFooter();

    const credit = screen.getByText("Built by").closest("a");
    expect(credit).toHaveAttribute("href", "https://uhndata.io");
    expect(screen.getByRole("img", { name: "DATA" })).toBeInTheDocument();
  });

  it("shows only the credit when no affiliation is configured", () => {
    const { container } = renderFooter();

    expect(container.querySelectorAll("img")).toHaveLength(1);
  });

  it("displays the platform version when the page metadata provides it", () => {
    seedMetas({ platformName: "IAP", version: "1.2.3" });

    renderFooter();

    expect(screen.getByText("IAP 1.2.3")).toBeInTheDocument();
  });

  it("shows the affiliated institution's logo when the deployment configures one", () => {
    seedMetas({ affiliationLogoLight: "/hospital.png", affiliationName: "Some Hospital" });

    renderFooter();

    expect(screen.getByRole("img", { name: "Some Hospital" })).toHaveAttribute("src", "/hospital.png");
    expect(screen.getByRole("img", { name: "DATA" })).toBeInTheDocument();
  });

  it("renders the registered footer links, in-app for paths and in a new tab for full URLs", async () => {
    mockedLoadExtensions.mockResolvedValue([
      { "iap:extensionName": "FAQ", "iap:targetURL": "/faq" },
      { "iap:extensionName": "Report a bug", "iap:targetURL": "https://tracker.example.com" },
    ]);

    renderFooter();

    const faq = await screen.findByRole("link", { name: "FAQ" });
    expect(faq).toHaveAttribute("href", "/faq");
    expect(faq).not.toHaveAttribute("target");
    const bugs = screen.getByRole("link", { name: "Report a bug" });
    expect(bugs).toHaveAttribute("href", "https://tracker.example.com");
    expect(bugs).toHaveAttribute("target", "_blank");
    expect(mockedLoadExtensions).toHaveBeenCalledWith("FooterLink");
  });
});
