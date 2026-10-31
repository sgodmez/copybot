; Copybot installer (Inno Setup 6): per user without administrator rights, or for all users (the wizard asks).
; Build: ISCC.exe /DAppVersion=<version> [/DAppNumericVersion=<X.Y.Z>] /DSourceDir=<path of target\dist\Copybot> /O<folder> copybot.iss

#ifndef AppVersion
  #error AppVersion is not defined: pass /DAppVersion=<version>
#endif
#ifndef SourceDir
  #error SourceDir is not defined: pass /DSourceDir=<folder of the jpackage app image>
#endif

[Setup]
; fixed AppId: it lets a new version install over the previous one
AppId={{C3DBE523-0904-4027-A210-35496B3C38EB}
AppName=Copybot
AppVersion={#AppVersion}
; the file version of the setup must be numbers only: X.Y.Z without the qualifier of a beta (1.3.0-beta.1), passed
; by the release workflow; without it Inno Setup takes AppVersion when it is numeric, else 0.0.0.0
#ifdef AppNumericVersion
VersionInfoVersion={#AppNumericVersion}
#endif
AppPublisher=com.copybot
DefaultDirName={autopf}\Copybot
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
; a silent update started by the application closes it, then restarts it only with /RELAUNCH
CloseApplications=yes
RestartApplications=no
OutputBaseFilename=copybot-{#AppVersion}-setup
UninstallDisplayIcon={app}\Copybot.exe
SetupIconFile={#SourcePath}..\main\resources\com\copybot\ui\Copybot.ico
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "french"; MessagesFile: "compiler:Languages\French.isl"
Name: "italian"; MessagesFile: "compiler:Languages\Italian.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[InstallDelete]
; the application image is replaced as a whole (as the portable mirror does): a file dropped by the new version does
; not stay behind; plugins and the data (config.json, prefs.json, logs) are elsewhere and stay
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Copybot"; Filename: "{app}\Copybot.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\Copybot"; Filename: "{app}\Copybot.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\Copybot.exe"; WorkingDir: "{app}"; Description: "{cm:LaunchProgram,Copybot}"; Flags: nowait postinstall skipifsilent
; update started by the application (/SILENT ... /RELAUNCH): the application restarts without a final page
Filename: "{app}\Copybot.exe"; WorkingDir: "{app}"; Flags: nowait runasoriginaluser; Check: Relaunch

[Code]
// True for a silent setup with /RELAUNCH on the command line (no value: the param function does not fit)
function Relaunch: Boolean;
var
  I: Integer;
begin
  Result := False;
  if not WizardSilent then
    Exit;
  for I := 1 to ParamCount do
    if CompareText(ParamStr(I), '/RELAUNCH') = 0 then
    begin
      Result := True;
      Exit;
    end;
end;
