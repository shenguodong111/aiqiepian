' AI QiePian hidden launcher wrapper (run by the Desktop shortcut; no console window)
Dim sh, fso, root
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
root = fso.GetParentFolderName(fso.GetFile(WScript.ScriptFullName).Path)
sh.CurrentDirectory = root
' 0 = hidden window; False = do not wait
sh.Run """" & root & "\launch-app.bat""", 0, False
