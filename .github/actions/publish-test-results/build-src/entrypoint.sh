#!/usr/bin/env bash

cd /srv

cp ${INPUT_PROJECT_ROOT}/cicd/local-cicd.sh .
source ./local-cicd.sh
source ./test-results.sh

copyResults
trackCoreTests ${INPUT_TESTS_RUN_EXIT_CODE}
appendLogLocation
checkForToken
persistResults
setOutputs
printStatus
