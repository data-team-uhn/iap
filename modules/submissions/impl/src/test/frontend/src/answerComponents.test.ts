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

import {
  clearAnswerComponents,
  registerAnswerComponent,
  type AnswerComponent,
  type AnswerComponentCandidate,
} from "@iap/submissions/answerComponents";

const Stub = (() => null) as AnswerComponent;

describe("the answer component registry", () => {
  // The candidates live on `window`, which the files in one process share, so another file's components may be
  // there
  beforeEach(() => {
    clearAnswerComponents();
  });

  afterEach(() => {
    clearAnswerComponents();
  });

  // Spelled out rather than read from the module: the name is what a separately built bundle
  // contributing a question type agrees on
  it("keeps its candidates where a separately built bundle finds them", () => {
    const candidate: AnswerComponentCandidate = () => [ Stub, 50 ];

    registerAnswerComponent(candidate);

    expect((window as unknown as Record<string, unknown>).__iapAnswerComponents).toEqual([ candidate ]);
  });
});
