# Copyright 2022-2026 DATA @ UHN. See the NOTICE file
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

# Use Docker multi-stage building so that the production image may be
# slim yet still be self-contained with all the necessary dependency
# JARs

# Start from a small Alpine Linux base image
FROM alpine:3.23

# Install dependency Alpine Linux packages and apply any available package upgrades
RUN apk update
RUN apk add --upgrade apk-tools
RUN apk upgrade --available
RUN apk add \
  openjdk21-jre \
  python3 \
  tzdata \
  bash

# Optional: enable remote debugging at port 5005
ENV DEBUG=

# Put everything in /opt/iap
RUN mkdir -p /opt/iap/.iap-data/
WORKDIR /opt/iap/
COPY target/dependency/org.apache.sling.feature.launcher/ org.apache.sling.feature.launcher/
COPY target/${project.artifactId}-${project.version}-core_mongo_far.far .
COPY target/${project.artifactId}-${project.version}-core_tar_far.far .
COPY logback.xml ./.iap-data/
COPY mailcap /root/.mailcap
COPY get_project_dependency_features.py /get_project_dependency_features.py
COPY sling-features.json /sling-features.json

# This is where Sling stores its data
# Make this a volume which can be persisted between different container versions
VOLUME ["/opt/iap/.iap-data"]

# Expose the 8080 port where normal HTTP connections are expected
EXPOSE 8080

# EXPOSE is just an indication, no port is actually published unless done explicitly when starting the container,
# so it is safe to always declare it here, even when debug mode isn't active
EXPOSE 5005

# Copy in the dependency JARs
COPY --from=0 /root/.m2 /root/.m2

# Copy in the build metadata
COPY --from=0 /metadata /metadata

# Copy in the generic IAP logo
COPY --from=0 /iap/modules/homepage/src/main/media/SLING-INF/content/libs/iap/resources/media/default/iap-logo.svg /metadata/logo.svg

# Create the ~/.iap-generic-m2/repository empty directory
RUN mkdir -p /root/.iap-generic-m2/repository

# This is the default command executed when starting the container
COPY docker_entry.sh /docker_entry.sh
RUN chmod +x /docker_entry.sh
ENTRYPOINT /docker_entry.sh ${project.artifactId} ${project.version}
