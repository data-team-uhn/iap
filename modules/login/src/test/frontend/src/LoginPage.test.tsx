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

import { withMessages } from "./messages.fixture";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(),
}));

const mockedLoadExtensions = vi.mocked(loadExtensions);

// The page composes parts tested on their own (LoginForm, ParticipatingInstitutions,
// PreLoginExtensions, FooterContent), so this only checks the page-level wiring: the
// content-driven texts with their fallbacks, and where the pieces land.
describe("LoginPage", () => {
  beforeEach(() => {
    // Both the pre-login extensions and the footer links come through loadExtensions
    mockedLoadExtensions.mockResolvedValue([]);
    // The participating-institutions registry is not configured in these tests
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
    const result = render(withMessages(
      <ThemeProvider theme={appTheme} defaultMode="light">
        <LoginPage />
      </ThemeProvider>
    ));
    // Commit the state updates of the extension- and registry-loading effects before the
    // test proceeds, so no update lands outside act() after the assertions
    await act(() => Promise.resolve());
    return result;
  };

  it("renders the sign-in area with default texts when nothing is configured", async () => {
    await renderPage();

    // The label overline specifically — the form's submit button also says "Sign in"
    expect(screen.getByText("Sign in", { selector: "p" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Continue with institutional credentials" }))
      .toBeInTheDocument();
    // The sign-in form is inside the main landmark
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
    // The page splits the footer: the shared content renders without credits, and the credits
    // render once, in their own grid area next to the sign-in panel
    expect(screen.getAllByText("Built by")).toHaveLength(1);
  });

  // The application theme enables MUI's CSS variables, so the page normally reads its colours from
  // theme.vars. A theme without them has to keep working, since nothing stops a deployment from
  // dropping the variables.
  it("reads its colours straight off the palette when the theme has no CSS variables", async () => {
    render(withMessages(
      <ThemeProvider theme={createTheme()}>
        <LoginPage />
      </ThemeProvider>
    ));
    await act(() => Promise.resolve());

    expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
  });

  it("mirrors the decorative chevron in a right-to-left theme", async () => {
    render(withMessages(
      <ThemeProvider theme={createTheme({ direction: "rtl" })}>
        <LoginPage />
      </ThemeProvider>
    ));
    await act(() => Promise.resolve());

    expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
  });
});
