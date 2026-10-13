@rem
@rem  Gradle wrapper startup script for Windows
@rem

if "%DEBUG%"=="" @echo off
setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

set WRAPPER_JAR="%APP_HOME%gradle\wrapper\gradle-wrapper.jar"

if defined JAVA_HOME (
    set JAVACMD="%JAVA_HOME%\bin\java.exe"
) else (
    set JAVACMD=java
)

"%JAVACMD%" %JAVA_OPTS% -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
