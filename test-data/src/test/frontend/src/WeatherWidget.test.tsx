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
import WeatherWidget from "@iap/test-data/WeatherWidget";

// Label to figure, in the order they are laid out across the strip below the divider.
const STATS = [
  ["High", "24°"],
  ["Low", "17°"],
  ["Humidity", "58%"],
  ["Wind", "12 km/h"],
];

const renderWidget = () => render(
  <ThemeProvider theme={appTheme} defaultMode="light">
    <WeatherWidget />
  </ThemeProvider>
);

describe("WeatherWidget", () => {
  it("names the place it is reporting on", () => {
    renderWidget();

    expect(screen.getByText("Toronto")).toBeInTheDocument();
  });

  it("leads with the current temperature and conditions", () => {
    renderWidget();

    expect(screen.getByText("21°")).toBeInTheDocument();
    expect(screen.getByText("Partly cloudy")).toBeInTheDocument();
  });

  // Each figure only means anything next to its own label, so the pairing is what gets asserted,
  // not the mere presence of eight strings somewhere on the widget.
  it("pairs every figure with its own label", () => {
    renderWidget();

    STATS.forEach(([label, value]) => {
      expect(screen.getByText(label).parentElement).toHaveTextContent(`${label}${value}`);
    });
  });

  it("lays the figures out in order", () => {
    renderWidget();

    const rendered = screen.getByText(STATS[0][0]).closest("div")?.parentElement?.textContent;

    expect(rendered).toBe(STATS.map(([label, value]) => label + value).join(""));
  });

  it("separates the summary from the detailed figures", () => {
    renderWidget();

    expect(screen.getByRole("separator")).toBeInTheDocument();
  });

  // The dashboard draws the frame and the "Weather" title around the widget — as an h6 (see
  // Widget.tsx) — so anything in here that MUI's variant mapping would turn into a heading lands a
  // second, competing entry in the document outline. Both the big temperature and the city name
  // only borrow their type scale; neither is a section of the page.
  it("borrows the heading type scale without contributing a heading", () => {
    renderWidget();

    expect(screen.getByText("21°").tagName).toBe("P");
    expect(screen.getByText("Toronto").tagName).toBe("P");
    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
  });
});
