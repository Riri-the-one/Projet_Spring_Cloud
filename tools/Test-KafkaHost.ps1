param([ValidateRange(1,65535)][int]$Port = 29092)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$probe = Join-Path $PSScriptRoot 'kafka-probe'
$wrapper = if ($env:OS -eq 'Windows_NT') { Join-Path $projectRoot 'mvnw.cmd' } else { Join-Path $projectRoot 'mvnw' }
$classpathFile = Join-Path $probe 'target/client-classpath.txt'
& $wrapper -B -ntp -f (Join-Path $probe 'pom.xml') compile dependency:build-classpath "-Dmdep.outputFile=$classpathFile"
if ($LASTEXITCODE -ne 0) { throw 'Compilation du client officiel Kafka impossible' }
$classpath = (Join-Path $probe 'target/classes') + [IO.Path]::PathSeparator + [IO.File]::ReadAllText($classpathFile).Trim()
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java' } else { 'java' }
& $java -cp $classpath com.ticketflow.tools.KafkaHostProbe "127.0.0.1:$Port"
if ($LASTEXITCODE -ne 0) { throw 'Echec de publication/consommation Kafka depuis le systeme hote' }
