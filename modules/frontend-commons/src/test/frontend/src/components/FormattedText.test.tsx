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

import { render, screen } from "@testing-library/react";

import FormattedText from "@iap/frontend-commons/components/FormattedText";

describe("FormattedText", () => {
  it("renders markdown formatting", () => {
    render(<FormattedText>{"Some **important** and *subtle* text"}</FormattedText>);

    expect(screen.getByText("important").tagName).toBe("STRONG");
    expect(screen.getByText("subtle").tagName).toBe("EM");
  });

  it("renders GitHub-flavored markdown extensions", () => {
    render(<FormattedText>{"An ~~obsolete~~ statement"}</FormattedText>);

    expect(screen.getByText("obsolete").tagName).toBe("DEL");
  });

  it("renders links", () => {
    render(<FormattedText>{"See [the docs](https://example.org/docs)"}</FormattedText>);

    expect(screen.getByRole("link", { name: "the docs" })).toHaveAttribute("href", "https://example.org/docs");
  });

  it("does not render raw HTML from the markdown source", () => {
    const { container } = render(<FormattedText>{"A <b>bold</b> <script>alert(1)</script>claim"}</FormattedText>);

    expect(container.querySelector("b")).toBeNull();
    expect(container.querySelector("script")).toBeNull();
  });

  it("forwards Typography props to the wrapper", () => {
    const { container } = render(<FormattedText variant="subtitle1">Plain text</FormattedText>);

    const wrapper = container.firstElementChild as HTMLElement;
    expect(wrapper.tagName).toBe("DIV");
    expect(wrapper.className).toContain("MuiTypography-subtitle1");
  });

  it("renders nothing for empty content", () => {
    const { container } = render(<FormattedText />);

    expect((container.firstElementChild as HTMLElement).textContent).toBe("");
  });
});
