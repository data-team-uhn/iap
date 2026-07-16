#!/bin/bash

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

BIND_TESTS=2
BIND_TEST_SPACING=30

TERMINAL_NOCOLOR='\033[0m'
TERMINAL_RED='\033[0;31m'
TERMINAL_GREEN='\033[0;32m'
TERMINAL_YELLOW='\033[0;33m'

#CTRL+C should stop everything started by this script
trap ctrl_c INT
function ctrl_c() {
  if [ ! -z $KEYCLOAK_HEADERMOD_HTTP_PROXY_PID ]
  then
    echo "Shutting down keycloak_headermod_http_proxy.js"
    kill $KEYCLOAK_HEADERMOD_HTTP_PROXY_PID
    wait $KEYCLOAK_HEADERMOD_HTTP_PROXY_PID
  fi
  echo "Shutting down IAP"
  kill $IAP_PID
  wait $IAP_PID
  exit
}

function check_iap_running() {
  jobs -pr | grep '^'$IAP_PID'$' > /dev/null
}

function print_length_of() {
  local i
  for i in `seq 1 $(echo -n "$1" | wc -c)`
  do
    echo -n "$2"
  done
  #Print extra
  for i in `seq 1 $3`
  do
    echo -n "$2"
  done
}

function print_pad_right() {
  printf "%-$2s" "$1"
}

function get_error_log_last_modified() {
  echo "$((stat --format="%.Y" .iap-data/logs/error.log 2>/dev/null) || echo 0.00)"
}

function handle_iap_java_fail() {
  echo -e "${TERMINAL_RED}********************************************$(print_length_of $BIND_PORT '*' 4)${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_RED}*                                           $(print_length_of $BIND_PORT ' ' 3)*${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_RED}*   The IAP Java process has failed at port ${BIND_PORT}   *${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_RED}*                                           $(print_length_of $BIND_PORT ' ' 3)*${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_RED}********************************************$(print_length_of $BIND_PORT '*' 4)${TERMINAL_NOCOLOR}"
  exit -1
}

function handle_tcp_bind_fail() {
  echo -e "${TERMINAL_RED}*******************************$(print_length_of $BIND_PORT '*' 4)${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_RED}*                              $(print_length_of $BIND_PORT ' ' 3)*${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_RED}*   Unable to bind to TCP port ${BIND_PORT}   *${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_RED}*                              $(print_length_of $BIND_PORT ' ' 3)*${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_RED}*******************************$(print_length_of $BIND_PORT '*' 4)${TERMINAL_NOCOLOR}"
  exit -1
}

function handle_tcp_bind_ok_optimal() {
  echo -e "${TERMINAL_GREEN}***************************${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_GREEN}*                         *${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_GREEN}*   IAP Socket BIND: OK   *${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_GREEN}*                         *${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_GREEN}***************************${TERMINAL_NOCOLOR}"
}

function handle_tcp_bind_ok_suboptimal() {
  echo -e "${TERMINAL_YELLOW}*******************************************************${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_YELLOW}*                                                     *${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_YELLOW}*   IAP Socket BIND: OK - used suboptimal bind test   *${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_YELLOW}*                                                     *${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_YELLOW}*******************************************************${TERMINAL_NOCOLOR}"
}

function message_started_iap() {
  if [ -z $KEYCLOAK_HEADERMOD_HTTP_PROXY_PID ]
  then
    echo -e "${TERMINAL_GREEN}************************$(print_length_of $BIND_PORT '*' 4)${TERMINAL_NOCOLOR}"
    echo -e "${TERMINAL_GREEN}*                       $(print_length_of $BIND_PORT ' ' 3)*${TERMINAL_NOCOLOR}"
    echo -e "${TERMINAL_GREEN}*   Started IAP at port ${BIND_PORT}   *${TERMINAL_NOCOLOR}"
    echo -e "${TERMINAL_GREEN}*                       $(print_length_of $BIND_PORT ' ' 3)*${TERMINAL_NOCOLOR}"
    echo -e "${TERMINAL_GREEN}************************$(print_length_of $BIND_PORT '*' 4)${TERMINAL_NOCOLOR}"
  else
    echo -e "${TERMINAL_GREEN}***************************************************${TERMINAL_NOCOLOR}"
    echo -e "${TERMINAL_GREEN}*                                                 *${TERMINAL_NOCOLOR}"
    echo -e "${TERMINAL_GREEN}*   Started IAP at port $(print_pad_right ${BIND_PORT} 21)   *${TERMINAL_NOCOLOR}"
    echo -e "${TERMINAL_GREEN}*   Use port ${HEADERMOD_PROXY_LISTEN_PORT} for SAML + local Sling login.   *${TERMINAL_NOCOLOR}"
    echo -e "${TERMINAL_GREEN}*                                                 *${TERMINAL_NOCOLOR}"
    echo -e "${TERMINAL_GREEN}***************************************************${TERMINAL_NOCOLOR}"
  fi
}

function message_connect_jdb() {
  echo -e "${TERMINAL_YELLOW}******************************************************************${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_YELLOW}*                                                                *${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_YELLOW}* Please connect JDB to localhost:5005 to continue with startup. *${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_YELLOW}* jdb -attach 5005                                               *${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_YELLOW}*                                                                *${TERMINAL_NOCOLOR}"
  echo -e "${TERMINAL_YELLOW}******************************************************************${TERMINAL_NOCOLOR}"
}

function get_iap_version() {
  PLATFORM_VERSION=$(cat pom.xml | grep --max-count=1 '<version>' | cut '-d>' -f2 | cut '-d<' -f1)
  echo PLATFORM_VERSION $PLATFORM_VERSION
}

#Determine the port that IAP is to bind to
BIND_PORT=8080

#Check if the psutil Python module is installed
python3 -c 'import psutil' 2>/dev/null && PSUTIL_INSTALLED=true || PSUTIL_INSTALLED=false

#If we are in WSL, psutil will not work, therefore act as if it is not installed
python3 -c 'import os; import sys; sys.exit(1 * ("Microsoft" not in os.uname().release))' && PSUTIL_INSTALLED=false

#If we are in macOS, psutil will not work, therefore act as if it is not installed
python3 -c 'import platform; import sys; sys.exit(1 * ("Darwin" != platform.system()))' && PSUTIL_INSTALLED=false

#If psutil is not installed, simply check if BIND_PORT is available now,
# and therefore will likely be available in the very near future
if [ $PSUTIL_INSTALLED = false ]
then
  python3 tools/HostConfig/check_tcp_available.py --tcp_port $BIND_PORT || handle_tcp_bind_fail
fi

# Filter the parameters to allow a less verbose start command, like `-p` to specify the port, or using `VERSION` to refer to the current version.
declare -a ARGS=("$@")
# Unset has strange effect on arrays, it leaves holes that somehow don't count towards the length of the array, so we must manually keep track of the index of the last element.
declare -i ARGS_LENGTH=${#ARGS[@]}
# Storage engine: default is TAR storage, allow switching to Mongo
declare OAK_STORAGE="tar"
declare PERMISSIONS_EXPLICITLY_SET="false"
# Are we using the Cloud-IAM.com Keycloak demo instance?
declare CLOUD_IAM_DEMO="false"
# Is SAML authentication enabled?
declare SAML_IN_USE="false"
# Should any flags be passed to Java to enable debugging with JDB?
declare JAVA_DEBUGGING_FLAGS=""
get_iap_version

for ((i=0; i<${ARGS_LENGTH}; ++i));
do
  if [[ ${ARGS[$i]} == '-p' || ${ARGS[$i]} == '--port' ]]
  then
    unset ARGS[$i]
    i=${i}+1
    BIND_PORT=${ARGS[$i]}
    unset ARGS[$i]
  elif [[ ${ARGS[$i]} == '--permissions' ]]
  then
    PERMISSIONS_EXPLICITLY_SET="true"
    unset ARGS[$i]
    i=${i}+1
    PERMISSIONS=${ARGS[$i]}
    unset ARGS[$i]
  elif [[ ${ARGS[$i]} == '--mongo' ]]
  then
    unset ARGS[$i]
    OAK_STORAGE="mongo"
  elif [[ ${ARGS[$i]} == '--dev' ]]
  then
    unset ARGS[$i]
    ARGS[$ARGS_LENGTH]=-f
    ARGS_LENGTH=${ARGS_LENGTH}+1
    ARGS[$ARGS_LENGTH]=mvn:io.uhndata.iap/iap-packaging-front-slingfeature/${PLATFORM_VERSION}/slingosgifeature/composum
    ARGS_LENGTH=${ARGS_LENGTH}+1
  elif [[ ${ARGS[$i]} == '--debug' ]]
  then
    unset ARGS[$i]
    JAVA_DEBUGGING_FLAGS="-Xdebug -Xnoagent -Djava.compiler=NONE -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"
  elif [[ ${ARGS[$i]} == '--test' ]]
  then
    RUNMODE_TEST=true
    unset ARGS[$i]
    ARGS[$ARGS_LENGTH]=-f
    ARGS_LENGTH=${ARGS_LENGTH}+1
    ARGS[$ARGS_LENGTH]=mvn:io.uhndata.iap/iap-test-forms/${PLATFORM_VERSION}/slingosgifeature
    ARGS_LENGTH=${ARGS_LENGTH}+1
  else
    ARGS[$i]=${ARGS[$i]@P}
    ARGS[$i]=${ARGS[$i]//VERSION/${PLATFORM_VERSION}}
  fi
done

PROJECT_SPECIFIED=false
if [ -z $PROJECT_VERSION ]
then
  PROJECT_VERSION=$PLATFORM_VERSION
fi
for ((i=0; i<${ARGS_LENGTH}; ++i));
do
  if [[ ${ARGS[$i]} == '-P' || ${ARGS[$i]} == '--project' ]]
  then
    ARGS[$i]='-f'
    i=${i}+1
    PROJECTS=${ARGS[$i]//,/ }
    ARGS[$i]=''
    for PROJECT in $PROJECTS
    do
      # Support both "iap4project" and just "project": make sure the PROJECT starts with "iap4"
      PROJECT="iap4${PROJECT#iap4}"
      ARGS[$i]=${ARGS[$i]},mvn:io.uhndata.iap/${PROJECT}/${PROJECT_VERSION}/slingosgifeature,$(PLATFORM_VERSION=${PLATFORM_VERSION} PROJECT_NAME=${PROJECT} PROJECT_VERSION=${PROJECT_VERSION} PERMISSIONS=${PERMISSIONS} python3 tools/Startup/get_project_dependency_features.py tools/Startup/core-sling-features.json)
      TEMPDEPDIR=$(mktemp -d)
      mvn --quiet --non-recursive dependency:copy -Dartifact=io.uhndata.iap:${PROJECT#iap4}-docker-packaging:${PROJECT_VERSION}:dependencies -DoutputDirectory=${TEMPDEPDIR} 2>&1 > /dev/null
      if [[ -f ${TEMPDEPDIR}/${PROJECT#iap4}-docker-packaging-${PROJECT_VERSION}.dependencies ]]
      then
        ARGS[$i]=${ARGS[$i]%,},$(PLATFORM_VERSION=${PLATFORM_VERSION} PROJECT_NAME=${PROJECT} PROJECT_VERSION=${PROJECT_VERSION} PERMISSIONS=${PERMISSIONS} python3 tools/Startup/get_project_dependency_features.py ${TEMPDEPDIR}/*.dependencies)
      fi
      rm -rf ${TEMPDEPDIR}
      PROJECT_SPECIFIED=true
    done
    ARGS[$i]=${ARGS[$i]#,}
  fi
done

# if [ $PROJECT_SPECIFIED = false ]
# then
#   ARGS[$ARGS_LENGTH]=-f
#   ARGS_LENGTH=${ARGS_LENGTH}+1
#   ARGS[$ARGS_LENGTH]=$(PLATFORM_VERSION=${PLATFORM_VERSION} PROJECT_NAME="" PROJECT_VERSION=${PROJECT_VERSION} PERMISSIONS=${PERMISSIONS} python3 tools/Startup/get_project_dependency_features.py tools/Startup/core-sling-features.json)
#   ARGS_LENGTH=${ARGS_LENGTH}+1
# fi

ERROR_LOG_LAST_MODIFIED_TIME_ORIGIN=$(get_error_log_last_modified)

export JAVA_OPTS="${JAVA_DEBUGGING_FLAGS} -Djdk.xml.entityExpansionLimit=0 -Dorg.osgi.service.http.port=${BIND_PORT}"

#Start IAP in the background
./packaging/target/dependency/org.apache.sling.feature.launcher/bin/launcher -u "file://$(realpath .mvnrepo),file://$(realpath "${HOME}/.m2/repository"),https://repo.maven.apache.org/maven2" -p .iap-data -c .iap-data/cache -f mvn:io.uhndata.iap/iap-packaging-front-slingfeature/${PLATFORM_VERSION}/slingosgifeature/core_${OAK_STORAGE} "${ARGS[@]}" &
IAP_PID=$!

if [ ! -z "$JAVA_DEBUGGING_FLAGS" ]
then
  message_connect_jdb
  # As soon as we see IAP writing to .iap-data/logs/error.log, we
  # can conclude that JDB has attached to the Java process.
  while (( $(echo "$(get_error_log_last_modified) <= $ERROR_LOG_LAST_MODIFIED_TIME_ORIGIN" | bc -l) ))
  do
    sleep 5
    echo "Waiting for JDB attachment..."
  done
fi

#Check to see if IAP was able to bind to the TCP port
#This is the more robust test that works only if psutil is installed
if [ $PSUTIL_INSTALLED = true ]
then
  for bind_test in `seq 0 $BIND_TESTS`
  do
    #Check if we have timed out
    if [ $bind_test = $BIND_TESTS ]
    then
      kill $IAP_PID
      wait $IAP_PID
      handle_tcp_bind_fail
    fi
    sleep $BIND_TEST_SPACING
    #Check if IAP was able to bind
    python3 tools/HostConfig/check_tcp_listen.py --tcp_port $BIND_PORT --pid $IAP_PID && break
    #If the IAP Java process has terminated, stop this script altogether
    check_iap_running || handle_iap_java_fail
  done
fi

if [ $PSUTIL_INSTALLED = true ]
then
  handle_tcp_bind_ok_optimal
else
  handle_tcp_bind_ok_suboptimal
fi

export IAP_URL="http://localhost:${BIND_PORT}"
#Wait for IAP to be ready
while true
do
  echo "Waiting for IAP to start"
  #If the IAP Java process has terminated, stop this script altogether
  check_iap_running || handle_iap_java_fail
  curl --fail $IAP_URL/system/sling/info.sessionInfo.json > /dev/null 2> /dev/null && break
  sleep 5
done

message_started_iap
#Stop this script if the IAP process terminates in failure
wait $IAP_PID
handle_iap_java_fail
