#!/bin/sh
# Gradle wrapper startup script.
# Resolves JAVA and runs the wrapper jar to download/use the right Gradle.
set -e

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD" \
    $JAVA_OPTS \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
