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
import { act, render, screen, waitFor } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import FooterContent, { FooterCredits } from "@iap/frontend-commons/components/FooterContent";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

vi.mock("@iap/ui-extension/extensionManager", () => ({
  loadExtensions: vi.fn(),
}));

const mockedLoadExtensions = vi.mocked(loadExtensions);

// The app shell's Footer.test exercises the common rendering (links, version, affiliation logo)
// through its wrapper, so this focuses on the behaviors specific to the shared component: the
// default anchor navigation, the label fallback, and the credits being omittable.
describe("FooterContent", () => {
  beforeEach(() => {
    mockedLoadExtensions.mockResolvedValue([]);
  });

  const renderContent = async (props: object = {}) => {
    const result = render(
      <ThemeProvider theme={appTheme} defaultMode="light">
        <FooterContent {...props} />
      </ThemeProvider>
    );
    // Commit the extension-loading state update before the test proceeds
    await act(() => Promise.resolve());
    return result;
  };

  it("leaves the target empty for a link that registers no URL", async () => {
    mockedLoadExtensions.mockResolvedValue([
      { "ext:name": "No target" },
    ]);

    await renderContent();

    // An empty href is not a link as far as accessibility is concerned, so the entry is found by
    // its text rather than by role
    const entry = await screen.findByText("No target");
    expect(entry.tagName).toBe("A");
    expect(entry).toHaveAttribute("href", "");
  });

  it("navigates in-app links with a plain full-page anchor by default", async () => {
    mockedLoadExtensions.mockResolvedValue([
      { "ext:name": "FAQ", "ext:targetURL": "/faq" },
    ]);

    await renderContent();

    const faq = await screen.findByRole("link", { name: "FAQ" });
    expect(faq).toHaveAttribute("href", "/faq");
    expect(faq).not.toHaveAttribute("target");
  });

  it("labels a link with its URL when no name is registered", async () => {
    mockedLoadExtensions.mockResolvedValue([
      { "ext:targetURL": "/faq" },
    ]);

    await renderContent();

    expect(await screen.findByRole("link", { name: "/faq" })).toHaveAttribute("href", "/faq");
  });

  it("omits the credits when the caller renders them elsewhere", async () => {
    await renderContent({ credits: false });

    expect(screen.queryByText("Built by")).not.toBeInTheDocument();
  });

  it("tolerates a failure to load the footer links", async () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);
    mockedLoadExtensions.mockRejectedValue(new Error("network error"));

    await renderContent();

    await waitFor(() => expect(consoleError).toHaveBeenCalled());
    expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
    consoleError.mockRestore();
  });
});

describe("FooterCredits", () => {
  const renderCredits = (mode: "light" | "dark") => render(
    <ThemeProvider theme={appTheme} defaultMode={mode}>
      <FooterCredits />
    </ThemeProvider>
  );

  it("uses the light-background logo variant in light mode", async () => {
    renderCredits("light");

    await waitFor(() => expect(screen.getByRole("img", { name: "DATA" }))
      .toHaveAttribute("src", "/libs/iap/resources/media/default/data-logo_light_bg.png"));
  });

  it("uses the dark-background logo variant in dark mode", async () => {
    renderCredits("dark");

    await waitFor(() => expect(screen.getByRole("img", { name: "DATA" }))
      .toHaveAttribute("src", "/libs/iap/resources/media/default/data-logo.png"));
  });

  it("follows the system scheme when that is what the deployment defaults to", async () => {
    render(
      <ThemeProvider theme={appTheme} defaultMode="system">
        <FooterCredits />
      </ThemeProvider>
    );

    // jsdom reports no colour-scheme preference, so the light-background variant stands in
    await waitFor(() => expect(screen.getByRole("img", { name: "DATA" }))
      .toHaveAttribute("src", "/libs/iap/resources/media/default/data-logo_light_bg.png"));
  });
});
