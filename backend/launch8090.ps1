$ErrorActionPreference = 'Stop'
$java = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot\bin\java.exe'
$jar  = 'target\aiqiepian-backend-1.0.0.jar'
$cwd  = 'C:\Users\25436\Documents\Qoder\2026-10-09\ab87a17e\backend'

# 直接由 WMI 在系统服务上下文创建 java（脱离当前会话进程树，避免被回收）；
# 日志交给 Spring 自身 --logging.file.name 写文件，不用 shell 重定向（规避嵌套引号问题）。
$commandLine = '"' + $java + '" -jar "' + $jar + '" --engine.mode=real --server.port=8090 --logging.file.name=run8090.log'

$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
    CommandLine      = $commandLine
    CurrentDirectory = $cwd
}
if ($r.ReturnValue -eq 0) {
    Write-Output ("DETACHED_PID=" + $r.ProcessId)
} else {
    Write-Output ("CREATE_FAILED ReturnValue=" + $r.ReturnValue)
}
