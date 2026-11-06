#!/bin/sh
# Gradle wrapper for Unix. On Windows use gradlew.bat.
set -e
APP_HOME=$( cd -P "${0%/*}" && pwd )
exec java -Dfile.encoding=UTF-8 -Xmx64m -Xms64m -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
