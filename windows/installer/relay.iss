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
OutputDir=output
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
#if AppPlatform == "x64"
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
#endif

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Relay"; Filename: "{app}\Relay.App.exe"
Name: "{autodesktop}\Relay"; Filename: "{app}\Relay.App.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Relay.App.exe"; Description: "{cm:LaunchProgram,Relay}"; Flags: nowait postinstall skipifsilent
