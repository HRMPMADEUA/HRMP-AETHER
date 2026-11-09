; TIREXNET Windows installer (Inno Setup 6). Compile with:
;   ISCC.exe /DSourceDir=<stage dir> /DOutputDir=<out dir> /DAppVersion=1.0.0 TIREXNET.iss
#ifndef SourceDir
  #error SourceDir must be defined
#endif
#ifndef OutputDir
  #define OutputDir "."
#endif
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{6F0B8E52-7A3D-4C1E-9B57-A1B2C3D4E5F6}
AppName=TIREXNET
AppVersion={#AppVersion}
AppPublisher=TIREXNET
DefaultDirName={autopf}\TIREXNET
DefaultGroupName=TIREXNET
DisableProgramGroupPage=yes
OutputDir={#OutputDir}
OutputBaseFilename=TIREXNET-Windows-x64-Setup
Compression=lzma2/max
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
MinVersion=10.0
WizardStyle=modern
UninstallDisplayIcon={app}\TIREXNET.exe
SetupIconFile={#SourceDir}\TIREXNET.ico
LicenseFile={#SourceDir}\THIRD_PARTY_NOTICES.md
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Shortcuts:"
Name: "startupicon"; Description: "Start TIREXNET minimized to the tray when I sign in to Windows"; GroupDescription: "Startup:"; Flags: unchecked

[Files]
Source: "{#SourceDir}\app\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#SourceDir}\engines\*"; DestDir: "{app}\engines"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#SourceDir}\licenses\*"; DestDir: "{app}\licenses"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#SourceDir}\THIRD_PARTY_NOTICES.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SourceDir}\README.md"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\TIREXNET"; Filename: "{app}\TIREXNET.exe"
Name: "{group}\Uninstall TIREXNET"; Filename: "{uninstallexe}"
Name: "{autodesktop}\TIREXNET"; Filename: "{app}\TIREXNET.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "TIREXNET"; ValueData: """{app}\TIREXNET.exe"""; Tasks: startupicon; Flags: uninsdeletevalue

[Run]
Filename: "{app}\TIREXNET.exe"; Description: "Launch TIREXNET"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; Removes leftovers of an interrupted session (routes, the WireGuard tunnel service, orphaned engine processes, system proxy).
Filename: "{app}\TIREXNET.exe"; Parameters: "--cleanup"; Flags: runhidden waituntilterminated; RunOnceId: "TirexCleanup"

[UninstallDelete]
; Only application-owned machine state is removed. Per-user profiles and settings (%APPDATA%\TIREXNET) are kept on purpose.
Type: filesandordirs; Name: "{commonappdata}\TIREXNET"
Type: filesandordirs; Name: "{app}"

[Code]
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if (CurUninstallStep = usPostUninstall) and (not UninstallSilent) then
    MsgBox('TIREXNET was removed.' + #13#10 + 'Your saved profiles and settings in %APPDATA%\TIREXNET were kept. Delete that folder manually if you no longer need them.', mbInformation, MB_OK);
end;
