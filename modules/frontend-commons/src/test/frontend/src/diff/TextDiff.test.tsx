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

import { type ReactNode } from "react";

import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";

import { appTheme } from "@iap/frontend-commons/appTheme";
import { compareText } from "@iap/frontend-commons/diff/contentDiffModel";
import TextDiff from "@iap/frontend-commons/diff/TextDiff";
import ValueChange from "@iap/frontend-commons/diff/ValueChange";

const renderInTheme = (node: ReactNode) => render(<ThemeProvider theme={appTheme}>{node}</ThemeProvider>);

describe("TextDiff", () => {
  it("shows a changed line as a deletion and its replacement as an insertion, marked with more than colour", () => {
    const { container } = renderInTheme(<TextDiff lines={compareText("Same\nYour age", "Same\nYour age in years")} />);

    expect(screen.getByText("Same").closest("ins, del")).toBeNull();
    expect(container.querySelector("del")).toHaveTextContent("−Your age");
    expect(container.querySelector("ins")).toHaveTextContent("+Your age in years");
    // The words that changed are set apart within their line
    expect(screen.getByText("in years").closest("ins")).toBe(container.querySelector("ins"));
  });

  it("keeps an empty line that did not change, so that paragraphs stay apart", () => {
    const { container } = renderInTheme(<TextDiff lines={compareText("Intro\n\nDetails", "Intro\n\nMore details")} />);

    expect(container.firstElementChild?.children[1].textContent).toBe("\u00a0");
  });

  it("shows nothing for two empty texts", () => {
    const { container } = renderInTheme(<TextDiff lines={[]} />);

    expect(container).toHaveTextContent("");
  });
});

describe("ValueChange", () => {
  it("shows what a value was and what it is", () => {
    const { container } = renderInTheme(<ValueChange before="Text" after="Number" />);

    expect(container.querySelector("del")).toHaveTextContent("Text");
    expect(container.querySelector("ins")).toHaveTextContent("Number");
    expect(container).toHaveTextContent("Text→Number");
  });

  it("shows only the side there is for a value added or removed", () => {
    const { container, rerender } = renderInTheme(<ValueChange after="Optional" />);
    expect(container.querySelector("del")).toBeNull();
    expect(container).toHaveTextContent(/^Optional$/);

    rerender(<ThemeProvider theme={appTheme}><ValueChange before="Required" /></ThemeProvider>);
    expect(container.querySelector("ins")).toBeNull();
    expect(container).toHaveTextContent(/^Required$/);
  });
});
