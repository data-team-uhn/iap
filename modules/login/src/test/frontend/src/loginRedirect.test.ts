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

import { loginRedirectPath } from '@iap/login/loginRedirect';

const onLoginPage = (search: string) => ({ pathname: "/login.html", search });

describe("loginRedirectPath", () => {
  it("returns the resource parameter when it is a relative path", () => {
    expect(loginRedirectPath(onLoginPage("?resource=%2FWorkflows"))).toBe("/Workflows");
    expect(loginRedirectPath(onLoginPage("?resource=%2Fcontent.html%2Fa%3Fb%3Dc"))).toBe("/content.html/a?b=c");
  });

  it("falls back to the homepage when there is no resource parameter", () => {
    expect(loginRedirectPath(onLoginPage(""))).toBe("/");
  });

  it("falls back to the current page when it is not the login page", () => {
    expect(loginRedirectPath({ pathname: "/somewhere.html", search: "" })).toBe("/somewhere.html");
  });

  it("rejects absolute and protocol-relative URLs", () => {
    expect(loginRedirectPath(onLoginPage("?resource=https%3A%2F%2Fevil.example"))).toBe("/");
    expect(loginRedirectPath(onLoginPage("?resource=%2F%2Fevil.example"))).toBe("/");
    expect(loginRedirectPath(onLoginPage("?resource=%2Fpath%3Fnext%3Dhttps%3A%2F%2Fevil.example"))).toBe("/");
  });

  it("rejects paths containing backslashes", () => {
    expect(loginRedirectPath(onLoginPage("?resource=%2F%5Cevil.example"))).toBe("/");
  });

  it("rejects relative paths not anchored at the root", () => {
    expect(loginRedirectPath(onLoginPage("?resource=evil.example"))).toBe("/");
  });
});
