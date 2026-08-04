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
import QuoteWidget from "@iap/test-data/QuoteWidget";

// The quote as it reads once the typographic entities in the source have been resolved and the
// line wrapping collapsed, which is what the DOM ends up holding.
const QUOTE = "“The best way to predict the future is to invent it. Really smart people with "
  + "reasonable funding can do just about anything that doesn’t violate too many of "
  + "Newton’s laws. The reason it hasn’t happened yet is that the people who could "
  + "do it are not the people who want to do it, and vice versa.”";

const ATTRIBUTION = "— Alan Kay, computer scientist, on the founding ideas of personal computing";

const renderWidget = () => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <QuoteWidget />
  </ThemeProvider>
);

describe("QuoteWidget", () => {
  it("shows the quote in full", () => {
    renderWidget();

    expect(screen.getByText(QUOTE)).toBeInTheDocument();
  });

  it("credits the quote", () => {
    renderWidget();

    expect(screen.getByText(ATTRIBUTION)).toBeInTheDocument();
  });

  // The point of the widget is to be a *quotation*, so the markup has to say so rather than only
  // looking the part.
  it("marks the quote up as a blockquote, and the credit not", () => {
    renderWidget();

    expect(screen.getByText(QUOTE).tagName).toBe("BLOCKQUOTE");
    expect(screen.getByText(ATTRIBUTION).tagName).not.toBe("BLOCKQUOTE");
  });

  it("sets the quote in italics", () => {
    renderWidget();

    expect(screen.getByText(QUOTE)).toHaveStyle({ fontStyle: "italic" });
  });

  // This widget exists to give the dashboard something tall to tile, and the dashboard draws the
  // frame and title around it — so it must not bring a heading of its own.
  it("contributes no heading of its own", () => {
    renderWidget();

    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
  });
});
