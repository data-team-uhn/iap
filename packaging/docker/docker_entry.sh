#!/bin/bash

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

# If we have not explicitly specified the file system as the Oak Repo
# back-end for data storage, use MongoDB
STORAGE=tar
[ -z $OAK_FILESYSTEM ] && STORAGE=mongo

#If inside a docker-compose environment, wait for a signal...
[ -z $INSIDE_DOCKER_COMPOSE ] || (while true; do (echo "IAP" | nc router 9999) && break; sleep 5; done)

#If (inside a docker-compose environment), we are supposed to wait for http://iapinitial:8080/ to start
[ -z $WAIT_FOR_INIT ] || (while true; do (wget -S --spider http://iapinitial:8080/ 2>&1 | grep 'HTTP/1.1 200 OK') && break; sleep 10; done)

PLATFORM_ARTIFACTID=$1
PLATFORM_VERSION=$2

if [ -z $PROJECT_VERSION ]
then
  PROJECT_VERSION=$PLATFORM_VERSION
fi

featureFlagString=""
if [ ! -z $PROJECT_NAME ] && [ ! -z $PROJECT_VERSION ]
then
  featureFlagString="$featureFlagString -f mvn:io.uhndata.iap/${PROJECT_NAME}/${PROJECT_VERSION}/slingosgifeature"
fi

if [ ! -z $ADDITIONAL_SLING_FEATURES ]
then
  featureFlagString="$featureFlagString -f ${ADDITIONAL_SLING_FEATURES@P}"
fi

# Read /sling-features.json and enable the features required for this project
PROJECT_REQUIRED_FEATURES=$(PLATFORM_VERSION=${PLATFORM_VERSION} PROJECT_NAME=${PROJECT_NAME} PROJECT_VERSION=${PROJECT_VERSION} PERMISSIONS=${PERMISSIONS} python3 /get_project_dependency_features.py /sling-features.json)
if [ ! -z $PROJECT_REQUIRED_FEATURES ]
then
  featureFlagString="$featureFlagString -f $PROJECT_REQUIRED_FEATURES"
fi

echo "STORAGE = $STORAGE"
echo "DEBUG = $DEBUG"
echo "PERMISSIONS = $PERMISSIONS"
echo "ADDITIONAL_SLING_FEATURES = $ADDITIONAL_SLING_FEATURES"
echo "PLATFORM_ARTIFACTID = $PLATFORM_ARTIFACTID"
echo "PLATFORM_VERSION = $PLATFORM_VERSION"
echo "PROJECT_NAME = $PROJECT_NAME"
echo "PROJECT_VERSION = $PROJECT_VERSION"

#Are we using an external MongoDB service for data storage?
EXT_MONGO_VARIABLES=""
if [ ! -z $EXTERNAL_MONGO_URI ]
then
  AUTH_EXTERNAL_MONGO_URI=$EXTERNAL_MONGO_URI
  if [ ! -z $MONGO_AUTH ]
  then
    AUTH_EXTERNAL_MONGO_URI="$MONGO_AUTH@$AUTH_EXTERNAL_MONGO_URI"
  fi
  if [ ! -z $CUSTOM_MONGO_DB_NAME ]
  then
    EXT_MONGO_VARIABLES="$EXT_MONGO_VARIABLES -V mongo.db=$CUSTOM_MONGO_DB_NAME"
  fi
  EXT_MONGO_VARIABLES="$EXT_MONGO_VARIABLES -V mongo.uri=$AUTH_EXTERNAL_MONGO_URI"
fi

SMTPS_VARIABLES=""
if [ ! -z $SMTPS_HOST ]
then
  SMTPS_VARIABLES="$SMTPS_VARIABLES -V emailnotifications.smtps.host=$SMTPS_HOST"
fi

#Should the SMTPS OSGi bundle be enabled?
if [[ "$SMTPS_ENABLED" == "true" ]]
then
  featureFlagString="$featureFlagString -f mvn:io.uhndata.iap/iap-email-notifications/${PLATFORM_VERSION}/slingosgifeature"
fi

featureFlagString=${featureFlagString//PLATFORM_VERSION/${PLATFORM_VERSION}}
featureFlagString=${featureFlagString//PROJECT_VERSION/${PROJECT_VERSION}}

if [[ "$SMTPS_LOCALHOST_PROXY" == "true" ]]
then
  keytool -import -trustcacerts -file /etc/cert/smtps_certificate.crt -keystore /etc/ssl/certs/java/cacerts -keypass changeit -storepass changeit -noprompt
  SMTPS_VARIABLES="$SMTPS_VARIABLES -V emailnotifications.smtps.checkserveridentity=false"
fi

if [[ "$SMTPS_LOCAL_TEST_CONTAINER" == "true" ]]
then
  SMTPS_VARIABLES="$SMTPS_VARIABLES -V emailnotifications.smtps.checkserveridentity=false"
  SMTPS_VARIABLES="$SMTPS_VARIABLES -V emailnotifications.smtps.host=smtps_test_container"
  SMTPS_VARIABLES="$SMTPS_VARIABLES -V emailnotifications.smtps.port=465"
fi

#Load all the SSL certs under /load_certs into Java's trusted CA keystore
for CERT_FILE in $(find /load_certs -type f -name "*.pem" -o -name "*.crt")
do
  echo "Adding $CERT_FILE to Java's trusted CA keystore"
  keytool -import -trustcacerts -file $CERT_FILE -keystore /etc/ssl/certs/java/cacerts -keypass changeit -storepass changeit -noprompt
done

#Execute the volume_mounted_init.sh script if it is present
[ -e /volume_mounted_init.sh ] && /volume_mounted_init.sh

export JAVA_OPTS="${JAVA_MEMORY_LIMIT_MB:+ -Xmx${JAVA_MEMORY_LIMIT_MB}m} ${DEBUG:+ -Xdebug -Xnoagent -Djava.compiler=NONE -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=*:5005} -Djdk.xml.entityExpansionLimit=0"
chmod +x ./org.apache.sling.feature.launcher/bin/launcher
./org.apache.sling.feature.launcher/bin/launcher -u "file://$(realpath ${HOME}/.m2/repository),file://$(realpath ${HOME}/.iap-generic-m2/repository),https://nexus.phenotips.org/nexus/content/groups/public,https://repo.maven.apache.org/maven2,https://repository.apache.org/content/groups/snapshots" -p .iap-data -c .iap-data/cache -f ./${PLATFORM_ARTIFACTID}-${PLATFORM_VERSION}-core_${STORAGE}_far.far${EXT_MONGO_VARIABLES}${SMTPS_VARIABLES}${featureFlagString}
