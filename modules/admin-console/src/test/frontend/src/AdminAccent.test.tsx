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

import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router";

import AdminAccent from "@iap/admin-console/AdminAccent";

const renderAt = (url: string) => render(
  <MemoryRouter initialEntries={[url]}>
    <AdminAccent />
  </MemoryRouter>
);

describe("AdminAccent", () => {
  it("marks the administration area", () => {
    expect(renderAt("/admin").container).not.toBeEmptyDOMElement();
    expect(renderAt("/admin/categories").container).not.toBeEmptyDOMElement();
  });

  it("renders nothing outside the administration area", () => {
    expect(renderAt("/").container).toBeEmptyDOMElement();
    // A sibling page whose name merely shares the prefix is not in the area
    expect(renderAt("/administrivia").container).toBeEmptyDOMElement();
  });
});
