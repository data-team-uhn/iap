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

import { renderHook } from "@testing-library/react";

import { type Enqueue, useWorkQueue } from "@iap/frontend-commons/useWorkQueue";

// A queue for the work on one submission, as the page hands one over
export function aQueue(): Enqueue {
  return renderHook(() => useWorkQueue()).result.current;
}

// Lets every promise callback already due run
export function settle(): Promise<void> {
  return new Promise(resolve => {
    setTimeout(resolve, 0);
  });
}
