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

import RedirectSignIn, { redirectSignInTarget } from "@iap/login/RedirectSignIn";

describe("redirectSignInTarget", () => {
  it("attaches the validated return path to the target endpoint", () => {
    const target = redirectSignInTarget({ "iap:targetURL": "/goto-external-login" });

    expect(target).toBe(`${window.location.origin}/goto-external-login?resource=${encodeURIComponent(window.location.pathname)}`);
  });

  it("returns null without a usable target", () => {
    expect(redirectSignInTarget({})).toBeNull();
    expect(redirectSignInTarget({ "iap:targetURL": "" })).toBeNull();
  });
});

describe("RedirectSignIn", () => {
  it("renders the action button, with the extension's label and hint", () => {
    render(<RedirectSignIn extension={{
      "iap:targetURL": "/goto-external-login",
      "iap:hint": "You will be redirected to your institution's sign-in page.",
    }} />);

    expect(screen.getByRole("button", { name: "Continue to sign-in" })).toBeInTheDocument();
    expect(screen.getByText("You will be redirected to your institution's sign-in page.")).toBeInTheDocument();
  });

  it("renders nothing without a target", () => {
    const { container } = render(<RedirectSignIn extension={{}} />);

    expect(container).toBeEmptyDOMElement();
  });
});
