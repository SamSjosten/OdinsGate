; Odin's Gate's Windows installer (launcher/README.md, "Installers"): the jpackage app image (OdinsGate.exe, app\ with
; the jars and the notices, runtime\ with the trimmed Java that keeps bin\java.exe) installed per user, with no admin
; rights, under %LOCALAPPDATA%\Programs\OdinsGate, with a Start menu entry, an optional desktop icon and an
; uninstaller. Built by Gradle's windowsInstaller task (package.gradle), which passes the three defines:
;   ISCC.exe /DAppVersion=0.1.0 /DAppImage=<the app image folder> /DOutputDir=<for OdinsGate-Setup.exe> OdinsGate.iss
; The names here are Names.java's (APP_NAME, VENDOR, IMAGE_NAME, WINDOWS_INSTALLER), which NamesTest checks, since
; this file can't read them. The launcher's data (%LOCALAPPDATA%\OdinsGate: the downloaded game, settings, logs) is
; the launcher's own and is left alone by the uninstaller, so a reinstall keeps it.
#ifndef AppVersion
  #error AppVersion, AppImage and OutputDir are needed: see the top of this file
#endif

[Setup]
AppId={{E2E41633-5558-42A9-90D7-53F349420322}
AppName=Odin's Gate
AppVersion={#AppVersion}
AppVerName=Odin's Gate {#AppVersion}
AppPublisher=Odin's Gate
DefaultDirName={autopf}\OdinsGate
DefaultGroupName=Odin's Gate
DisableProgramGroupPage=yes
; Per user, no elevation prompt: {autopf} is then the user's own Programs folder under %LOCALAPPDATA%.
PrivilegesRequired=lowest
OutputDir={#OutputDir}
OutputBaseFilename=OdinsGate-Setup
UninstallDisplayName=Odin's Gate
UninstallDisplayIcon={app}\OdinsGate.exe
; The runtime is 64-bit; Windows on ARM runs it through its x64 emulation (x64compatible).
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
; A launcher still open while a new installer runs is asked to close, and is not restarted by the installer.
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#AppImage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Odin's Gate"; Filename: "{app}\OdinsGate.exe"
Name: "{autodesktop}\Odin's Gate"; Filename: "{app}\OdinsGate.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\OdinsGate.exe"; Description: "{cm:LaunchProgram,Odin's Gate}"; Flags: nowait postinstall skipifsilent
