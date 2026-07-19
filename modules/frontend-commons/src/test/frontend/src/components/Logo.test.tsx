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

import Logo from "@iap/frontend-commons/components/Logo";

// Logo reads the institution branding from <meta> tags in the page head, so each
// test seeds the head and clears it afterwards.
describe("Logo", () => {
  afterEach(() => {
    document.head.innerHTML = "";
  });

  it("renders the institution logo named after the page title", () => {
    document.head.innerHTML =
      '<meta name="title" content="IAP" />' +
      '<meta name="logoLight" content="/logo.light.png" />';

    render(<Logo />);

    const img = screen.getByRole("img", { name: "IAP" });
    expect(img).toBeInTheDocument();
    expect(img).toHaveAttribute("src", "/logo.light.png");
  });

  it("renders the affiliation logo alongside the main one when present", () => {
    document.head.innerHTML =
      '<meta name="title" content="IAP" />' +
      '<meta name="logoLight" content="/logo.light.png" />' +
      '<meta name="affiliationLogoLight" content="/affiliation.light.png" />';

    const { container } = render(<Logo />);

    expect(container.querySelectorAll("img")).toHaveLength(2);
  });
});
