@echo off
if not defined JAVA_HOME (
    if exist "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot" (
        set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
    ) else if exist "C:\Users\ELCOT\.jdks\temurin-21.0.11" (
        set "JAVA_HOME=C:\Users\ELCOT\.jdks\temurin-21.0.11"
    )
)
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"

set "MVN_CMD=mvn"
if exist "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd" (
    set "MVN_CMD=C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd"
) else if exist "C:\Users\ELCOT\AppData\Local\Programs\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin\mvn.cmd" (
    set "MVN_CMD=C:\Users\ELCOT\AppData\Local\Programs\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin\mvn.cmd"
)

"%MVN_CMD%" compile -DskipTests
