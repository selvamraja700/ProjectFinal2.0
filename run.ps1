if (-not $env:JAVA_HOME) {
    if (Test-Path "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot") {
        $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
    } elseif (Test-Path "C:\Users\ELCOT\.jdks\temurin-21.0.11") {
        $env:JAVA_HOME = "C:\Users\ELCOT\.jdks\temurin-21.0.11"
    }
}
if ($env:JAVA_HOME) {
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}
$env:MAVEN_OPTS = "-Xmx256m"

$mvnCmd = "mvn"
if (Test-Path "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd") {
    $mvnCmd = "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd"
} elseif (Test-Path "C:\Users\ELCOT\AppData\Local\Programs\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin\mvn.cmd") {
    $mvnCmd = "C:\Users\ELCOT\AppData\Local\Programs\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin\mvn.cmd"
}

& $mvnCmd spring-boot:run
