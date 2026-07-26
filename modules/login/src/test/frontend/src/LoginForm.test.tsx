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

import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import LoginForm from "@iap/login/LoginForm";

const fillAndSubmit = () => {
  fireEvent.change(screen.getByLabelText(/Username/), { target: { value: "admin" } });
  fireEvent.change(screen.getByLabelText(/Password/), { target: { value: "secret" } });
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
};

describe("LoginForm", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("keeps the submit button disabled until both fields are filled", () => {
    render(<LoginForm onSuccess={vi.fn()} />);

    const button = screen.getByRole("button", { name: "Sign in" });
    expect(button).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/Username/), { target: { value: "admin" } });
    expect(button).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/Password/), { target: { value: "secret" } });
    expect(button).toBeEnabled();
  });

  it("posts the credentials to j_security_check and reports success", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 200 }));
    const onSuccess = vi.fn();
    render(<LoginForm onSuccess={onSuccess} />);

    fillAndSubmit();

    await waitFor(() => expect(onSuccess).toHaveBeenCalled());
    expect(fetchMock).toHaveBeenCalledWith("/j_security_check", expect.objectContaining({ method: "POST" }));
    const body = fetchMock.mock.calls[0][1]?.body as URLSearchParams;
    expect(body.get("j_username")).toBe("admin");
    expect(body.get("j_password")).toBe("secret");
    // j_validate makes Sling answer with a status code instead of a redirect
    expect(body.get("j_validate")).toBe("true");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows an error and does not report success on rejected credentials", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 403 }));
    const onSuccess = vi.fn();
    render(<LoginForm onSuccess={onSuccess} />);

    fillAndSubmit();

    expect(await screen.findByText("Invalid username or password")).toBeInTheDocument();
    expect(onSuccess).not.toHaveBeenCalled();
    // The form is usable again for another attempt
    expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled();
  });
});
