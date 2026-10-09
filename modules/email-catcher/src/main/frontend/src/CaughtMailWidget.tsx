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

import { CAUGHT_MAIL_PATH } from "./caughtMailModel";

/**
 * The dashboard summary of the mail catcher: whether it is on, and how much it has caught.
 *
 * Both halves are needed to say anything at all. A count on its own cannot distinguish "nothing has
 * been sent" from "everything that was sent went out by mail", and those call for opposite reactions
 * from somebody who came to the dashboard to check whether a notification worked.
 */
function CaughtMailWidget() {
  return <WidgetStatList url={CAUGHT_MAIL_PATH} name="caught mail" />;
}

export default CaughtMailWidget;
