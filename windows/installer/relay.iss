; Relay Windows installer (Inno Setup 6). Built headlessly in CI:
;   iscc /DAppVersion=x.y.z /DAppPlatform=x64 /DSourceDir=..\publish-x64 relay.iss
; Unsigned by design for now — SmartScreen trade-off documented in docs/release.md.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef AppPlatform
  #define AppPlatform "x64"
#endif
#ifndef SourceDir
  #define SourceDir "publish"
#endif

[Setup]
AppId={{7E2F0D4B-9A64-4E5D-B1C4-52A18D6A2C11}
AppName=Relay
AppVersion={#AppVersion}
AppPublisher=Relay open-source project
AppPublisherURL=https://github.com/Mahdi-mortazavi/relay
DefaultDirName={autopf}\Relay
DisableProgramGroupPage=yes
; Per-user install: no admin prompt, and the app only ever touches HKCU anyway.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
OutputBaseFilename=Relay-Setup-{#AppPlatform}-{#AppVersion}
; Tell Setup a session may be running, so an upgrade asks to close Relay
; (and lets Restart Manager try) instead of overwriting files underneath a
; live proxy session. Matches the mutex App.xaml.cs takes at startup.
AppMutex=Local\RelayAppSingleton
OutputDir=output
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
; The installer's own icon, and the one Apps & Features shows. Without these
; both fall back to Inno's default, which is the first and last impression a
; person gets of whether this is finished software.
SetupIconFile={#SourceDir}\Assets\Relay.ico
UninstallDisplayIcon={app}\Relay.App.exe
#if AppPlatform == "x64"
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
#endif

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
; A tray app is worth having at login: it is the thing you want already running
; when a laptop turns out to have no internet. Off by default -- deciding that
; for someone is exactly the behaviour that makes people distrust installers --
; and the same switch lives in Advanced, reading the same registry value.
Name: "startup"; Description: "Start Relay when I sign in"; GroupDescription: "Additional options"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
; IconFilename is explicit rather than inherited from the exe: Windows caches
; shortcut icons aggressively, and an upgrade that changes the exe's icon does
; not always repaint a shortcut that only points at the exe.
Name: "{autoprograms}\Relay"; Filename: "{app}\Relay.App.exe"; IconFilename: "{app}\Assets\Relay.ico"; Comment: "Share your phone's internet with this PC"
Name: "{autodesktop}\Relay"; Filename: "{app}\Relay.App.exe"; IconFilename: "{app}\Assets\Relay.ico"; Tasks: desktopicon

[Registry]
; The same value StartupRegistration.cs reads and writes, including the --tray
; argument, so the installer checkbox and the switch in Advanced are one setting
; rather than two that can disagree. uninsdeletevalue so uninstalling does not
; leave Windows trying to start something that is gone.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Relay"; ValueData: """{app}\Relay.App.exe"" --tray"; Flags: uninsdeletevalue; Tasks: startup

[Run]
Filename: "{app}\Relay.App.exe"; Description: "{cm:LaunchProgram,Relay}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; If a session is active at uninstall time, stop the app and restore the system
; proxy so the user isn't left with a dangling SOCKS proxy and no app to undo it.
Filename: "{sys}\taskkill.exe"; Parameters: "/f /im Relay.App.exe"; Flags: runhidden; RunOnceId: "StopRelay"
Filename: "{app}\Relay.App.exe"; Parameters: "--restore-proxy"; Flags: runhidden waituntilterminated skipifdoesntexist; RunOnceId: "RestoreProxy"

[UninstallDelete]
; The startup log is disposable, so it goes.
;
; proxy-backup.json deliberately does NOT. If the --restore-proxy step above
; failed for any reason, that file is the only surviving record of the user's
; original proxy/PAC settings — deleting it would turn a recoverable problem
; ("reinstall Relay and it repairs itself") into a permanently broken machine
; with no way back except editing Internet Options by hand. A few hundred bytes
; left behind is the cheaper mistake. Relay deletes it itself the moment a
; rollback succeeds.
Type: files; Name: "{localappdata}\Relay\startup-error.log"
