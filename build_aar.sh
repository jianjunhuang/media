#!/bin/bash

mkdir -p ./aar_output

./gradlew clean :lib-extractor:assembleRelease
if [ $? -ne 0 ]; then
    echo "Build extractor failed. Please check the output for errors."
    exit 1
fi
scp ./libraries/extractor/buildout/outputs/aar/lib-extractor-release.aar  ./aar_output/media3-lib-extractor-release.aar

./gradlew clean :lib-exoplayer:assembleRelease
if [ $? -ne 0 ]; then
    echo "Build exoplayer failed. Please check the output for errors."
    exit 1
fi
scp ./libraries/exoplayer/buildout/outputs/aar/lib-exoplayer-release.aar  ./aar_output/media3-lib-exoplayer-release.aar

./gradlew clean :lib-common:assembleRelease

if [ $? -ne 0 ]; then
    echo "Build common failed. Please check the output for errors."
    exit 1
fi
scp ./libraries/common/buildout/outputs/aar/lib-common-release.aar  ./aar_output/media3-lib-common-release.aar
