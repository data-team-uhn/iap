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

import CredentialsForm from "@iap/login/CredentialsForm";

// The form itself is LoginForm's business; what matters here is what happens once it succeeds.
vi.mock("@iap/login/LoginForm", () => ({
  default: ({ onSuccess }: { onSuccess: () => void }) => (
    <button type="button" onClick={onSuccess}>Sign in</button>
  ),
}));

describe("CredentialsForm", () => {
  it("navigates to the validated return path once signed in", async () => {
    const assign = vi.fn();
    Object.defineProperty(window, "location", {
      configurable: true,
      value: { ...window.location, assign, search: "?resource=/dashboard" },
    });

    render(<CredentialsForm />);
    screen.getByRole("button", { name: "Sign in" }).click();

    expect(assign).toHaveBeenCalledWith("/dashboard");
  });
});
