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
// The page buttons: Previous page and Next page, with which page is shown between them.
import StepControls from "./StepControls";

// The Previous page and Next page buttons, with "Page 3 of 10" between them. The buttons stay put as the
// page changes. In a narrow row, the text is cut short. Both buttons are disabled until the document has
// opened.
export default function PageControls({ page, pageCount, onShowPage }: {
  page?: number;
  pageCount?: number;
  onShowPage: (page: number) => void;
}) {
  // 0 until the document opens, which keeps both page buttons disabled.
  const shownPage = page ?? 0;
  const lastPage = pageCount ?? 0;

  return (
    <StepControls
      position={shownPage}
      count={lastPage}
      itemName="page"
      heading={pageCount === undefined ? "Page" : `Page ${shownPage} of ${lastPage}`}
      widest={[ `Page ${lastPage} of ${lastPage}` ]}
      variant="caption"
      shrinks
      onStep={onShowPage}
    />
  );
}
