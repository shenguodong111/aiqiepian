' AI QiePian hidden launcher wrapper (run by the Desktop shortcut; no console window)
Dim sh
Set sh = CreateObject("WScript.Shell")
sh.CurrentDirectory = "C:\Users\25436\Documents\Qoder\2026-10-09\ab87a17e"
' 0 = hidden window; False = do not wait
sh.Run """C:\Users\25436\Documents\Qoder\2026-10-09\ab87a17e\launch-app.bat""", 0, False
Set sh = Nothing
