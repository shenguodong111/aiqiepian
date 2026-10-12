$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME first (see env.bat in the project root).' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$jar  = 'target\aiqiepian-backend-1.0.0.jar'

# 直接由 WMI 在系统服务上下文创建 java（脱离当前会话进程树，避免被回收）；
# 日志交给 Spring 自身 --logging.file.name 写文件，不用 shell 重定向（规避嵌套引号问题）。
$commandLine = '"' + $java + '" -jar "' + $jar + '" --engine.mode=real --server.port=8090 --logging.file.name=run8090.log'

$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
    CommandLine      = $commandLine
    CurrentDirectory = $root
}
if ($r.ReturnValue -eq 0) {
    Write-Output ("DETACHED_PID=" + $r.ProcessId)
} else {
    Write-Output ("CREATE_FAILED ReturnValue=" + $r.ReturnValue)
}
