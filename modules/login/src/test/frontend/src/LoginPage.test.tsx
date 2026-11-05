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

import { ThemeProvider, createTheme } from "@mui/material/styles";
import { act, render, screen } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import LoginPage from "@iap/login/LoginPage";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(),
}));

const mockedLoadExtensions = vi.mocked(loadExtensions);

// The parts of the page have tests of their own. These check the page's texts, with their
// defaults, and where each part goes.
describe("LoginPage", () => {
  beforeEach(() => {
    // Both the pre-login extensions and the footer links come through loadExtensions
    mockedLoadExtensions.mockResolvedValue([]);
    // There is no registry of participating institutions
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 404 }));
  });

  afterEach(() => {
    vi.restoreAllMocks();
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

  const renderPage = async () => {
    const result = render(
      <ThemeProvider theme={appTheme} defaultMode="light">
        <LoginPage />
      </ThemeProvider>
    );
    // Lets the state updates of the loading effects happen before the assertions
    await act(() => Promise.resolve());
    return result;
  };

  it("renders the sign-in area with default texts when nothing is configured", async () => {
    await renderPage();

    // The label, not the submit button, which also says "Sign in"
    expect(screen.getByText("Sign in", { selector: "p" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Continue with institutional credentials" }))
      .toBeInTheDocument();
    expect(screen.getByRole("main")).toContainElement(screen.getByLabelText(/username/i));
    expect(mockedLoadExtensions).toHaveBeenCalled();
  });

  it("renders the configured page texts", async () => {
    seedMetas({
      tagline: "Institutional Authorization Platform",
      introText: "Submit **proposals** faster",
      signInLabel: "Welcome",
      signInHeading: "Use your hospital account",
    });

    await renderPage();

    expect(screen.getByText("Institutional Authorization Platform")).toBeInTheDocument();
    expect(screen.getByText("proposals").tagName).toBe("STRONG");
    expect(screen.getByText("Welcome")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Use your hospital account" })).toBeInTheDocument();
  });

  it("renders the brand panel and exactly one credits cluster", async () => {
    await renderPage();

    expect(screen.getByRole("region", { name: "About the platform" })).toBeInTheDocument();
    // The footer is shown without its credits, which have a place of their own
    expect(screen.getAllByText("Built by")).toHaveLength(1);
  });

  it("reads its colours straight off the palette when the theme has no CSS variables", async () => {
    render(
      <ThemeProvider theme={createTheme()}>
        <LoginPage />
      </ThemeProvider>
    );
    await act(() => Promise.resolve());

    expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
  });

  it("mirrors the decorative chevron in a right-to-left theme", async () => {
    render(
      <ThemeProvider theme={createTheme({ direction: "rtl" })}>
        <LoginPage />
      </ThemeProvider>
    );
    await act(() => Promise.resolve());

    expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
  });
});
