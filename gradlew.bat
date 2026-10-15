@rem Gradle wrapper for Windows
@rem Download and execute Gradle
@echo off
set DEFAULT_JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
if "%JAVA_HOME%"=="" set JAVA_HOME=%DEFAULT_JAVA_HOME%
set CLASSPATH=%~dp0gradle\wrapper\gradle-wrapper.jar
"%JAVA_HOME%\bin\java.exe" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
