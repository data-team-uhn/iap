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

import LanguageSwitcher from "@iap/frontend-commons/LanguageSwitcher";

describe("LanguageSwitcher", () => {
  beforeEach(() => {
    window.history.pushState({}, "", "/login");
  });

  it("names each language in that language", () => {
    // "Français", never "French": a reader who cannot read the current language cannot read the name of
    // the one they are looking for either, which is the entire situation this control exists for.
    render(<LanguageSwitcher languages={[ "en", "fr" ]} />);

    expect(screen.getByRole("link", { name: "English" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "français" })).toBeInTheDocument();
  });

  it("links to this same page in the other language", () => {
    render(<LanguageSwitcher languages={[ "en", "fr" ]} />);

    expect(screen.getByRole("link", { name: "français" })).toHaveAttribute("href", "/login?locale=fr");
  });

  it("keeps the rest of the URL as it was", () => {
    // Whatever else brought the reader here — a redirect target, say — has to survive changing language
    window.history.pushState({}, "", "/login?resource=%2Fadmin");

    render(<LanguageSwitcher languages={[ "en", "fr" ]} />);

    expect(screen.getByRole("link", { name: "français" }))
      .toHaveAttribute("href", "/login?resource=%2Fadmin&locale=fr");
  });

  it("marks the language already being read", () => {
    window.history.pushState({}, "", "/login?locale=fr");

    render(<LanguageSwitcher languages={[ "en", "fr" ]} />);

    expect(screen.getByRole("link", { name: "français" })).toHaveAttribute("aria-current", "true");
    expect(screen.getByRole("link", { name: "English" })).not.toHaveAttribute("aria-current");
  });

  it("still offers the language being read", () => {
    // Left as a link rather than flattened to text, so a reader scanning for their own language finds
    // every one of them in the same shape and the same place
    window.history.pushState({}, "", "/login?locale=fr");

    render(<LanguageSwitcher languages={[ "en", "fr" ]} />);

    expect(screen.getAllByRole("link")).toHaveLength(2);
  });

  it("shows nothing when there is only one language", () => {
    // A control offering one choice is furniture, not a choice
    const { container } = render(<LanguageSwitcher languages={[ "en" ]} />);

    expect(container).toBeEmptyDOMElement();
  });

  it("shows nothing when no language is configured", () => {
    const { container } = render(<LanguageSwitcher languages={[]} />);

    expect(container).toBeEmptyDOMElement();
  });

  it("falls back to the tag for a language it cannot name", () => {
    render(<LanguageSwitcher languages={[ "en", "zz" ]} />);

    expect(screen.getByRole("link", { name: "zz" })).toBeInTheDocument();
  });
});
