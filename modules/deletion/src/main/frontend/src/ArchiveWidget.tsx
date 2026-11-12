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

import WidgetStatList from "@iap/frontend-commons/components/WidgetStatList";

import { ARCHIVE_PATH } from "./archiveApi";

// An administration console widget counting the archive's entries: in the last day, the last week,
// and in total. The frame's header action, from the extension's `ext:actionLabel` and
// `ext:targetURL`, leads to the archive's page.
function ArchiveWidget() {
  return <WidgetStatList url={ARCHIVE_PATH} name="archive" />;
}

export default ArchiveWidget;
