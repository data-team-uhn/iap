# Copyright 2026 DATA @ UHN. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# A development-only mail server that files messages away instead of delivering them. The server
# needs nothing beyond the standard library, so the image is just Python and one script.

FROM python:3.13-alpine

COPY smtps_catcher.py /smtps_catcher.py

# The certificate and key are mounted in; the messages are written out.
VOLUME ["/mail"]
EXPOSE 465

# -u so that what it reports shows up in `docker compose logs` as it happens, rather than
# whenever the block buffer happens to fill.
ENTRYPOINT ["python3", "-u", "/smtps_catcher.py"]
