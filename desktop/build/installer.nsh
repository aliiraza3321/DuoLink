; ---------------------------------------------------------------------------
; DuoLink installer + uninstaller appearance
;
; The reference design is a cream page with the DuoLink app icon, a headline,
; a subtitle, a gold action button and one underlined text link. MUI's stock
; welcome page cannot show that, so this include replaces it with an nsDialogs
; page: the artwork (cream, faint waves, honeycomb, logo, copy) comes from
; build/make-installer-art.py, while the button and link are real Windows
; controls, so they are clickable, keyboard reachable and screen-reader
; visible. The wizard's own Back/Next/Cancel row is hidden on these pages
; because the artwork carries the page's only actions.
;
; electron-builder compiles the script twice; functions a pass does not use
; trigger warning 6010 and warnings are errors here, so each pass defines only
; the pages it shows.
; ---------------------------------------------------------------------------

!include "nsDialogs.nsh"
!include "LogicLib.nsh"
!include "WinMessages.nsh"

; MUI only colours its header strip from MUI_BGCOLOR; the page body and the
; footer come from the outer dialog's own colours, which is why the branded
; page used to sit inside a stock white window. MUI gives us a hook that runs
; right after it initialises that dialog.
!define MUI_CUSTOMFUNCTION_GUIINIT duolinkGuiInit

Function duolinkGuiInit
  ; Cream paper (#F3EEE2) with ink text (#1E1B16) - NSIS stores colours BGR.
  SetCtlColors $HWNDPARENT 0x161B1E 0xE2EEF3
  GetDlgItem $0 $HWNDPARENT 1028
  ${If} $0 != 0
    SetCtlColors $0 0x4E5A5F 0xE2EEF3
  ${EndIf}
FunctionEnd

; Palette used by the remaining MUI pages (directory, installing, finish).
; NSIS colours are BGR: 0xE2EEF3 = #F3EEE2 cream, 0x161B1E = #1E1B16 ink.
!define MUI_BGCOLOR 0xE2EEF3
!define MUI_TEXTCOLOR 0x161B1E
!define MUI_HEADER_TRANSPARENT_TEXT
!define MUI_INSTFILESPAGE_COLORS "0x161B1E 0xE2EEF3"
!define MUI_INSTFILESPAGE_PROGRESSBAR colored

; MUI2 owns .onGUIInit (MUI_FUNCTION_GUIINIT) and paints the wizard from these
; two defines, so they must stay as defines - defining our own callback here
; collides with it and breaks the build.

Var DL_PAGE
Var DL_IMG
Var DL_CTL

!ifndef BUILD_UNINSTALLER

; ---- installer pass --------------------------------------------------------

!macro customInit
  InitPluginsDir
  File /oname=$PLUGINSDIR\setup-page.bmp "${BUILD_RESOURCES_DIR}\setup-page.bmp"
  File /oname=$PLUGINSDIR\setup-pill.bmp "${BUILD_RESOURCES_DIR}\setup-pill.bmp"
  File /oname=$PLUGINSDIR\ready-page.bmp "${BUILD_RESOURCES_DIR}\ready-page.bmp"
  File /oname=$PLUGINSDIR\ready-open.bmp "${BUILD_RESOURCES_DIR}\ready-open.bmp"
  File /oname=$PLUGINSDIR\ready-close.bmp "${BUILD_RESOURCES_DIR}\ready-close.bmp"
!macroend

!macro customWelcomePage
  Page custom duolinkWelcomeCreate duolinkWelcomeLeave
!macroend

; The stock finish page says "Completed" over a grey form. This replaces it
; with the reference layout: "DuoLink is ready", the Android download QR that
; points at the GitHub release, and Open / Close.
!macro customFinishPage
  Page custom duolinkFinishCreate duolinkFinishLeave
!macroend

Function duolinkFinishCreate
  ${If} ${Silent}
    Abort
  ${EndIf}
  Call duolinkHideButtons
  SetCtlColors $HWNDPARENT 0x161B1E 0xE2EEF3
  nsDialogs::Create 1018
  Pop $DL_PAGE
  ${If} $DL_PAGE == error
    Abort
  ${EndIf}
  SetCtlColors $DL_PAGE 0x161B1E 0xE2EEF3

  ${NSD_CreateBitmap} 0 0 0 0 ""
  Pop $DL_IMG
  ${NSD_SetImage} $DL_IMG "$PLUGINSDIR\ready-page.bmp" $DL_CTL
  System::Call 'user32::SetWindowPos(p $DL_IMG, p 0, i 0, i 0, i 490, i 238, i 0x0014)'

  ; Open DuoLink (filled pill) and Close (outlined pill), both still images.
  ${NSD_CreateBitmap} 0 0 0 0 ""
  Pop $DL_CTL
  ${NSD_SetImage} $DL_CTL "$PLUGINSDIR\ready-open.bmp" $DL_IMG
  System::Call 'user32::SetWindowPos(p $DL_CTL, p 0, i 118, i 190, i 170, i 34, i 0x0014)'
  ${NSD_OnClick} $DL_CTL duolinkOpenApp

  ${NSD_CreateBitmap} 0 0 0 0 ""
  Pop $DL_CTL
  ${NSD_SetImage} $DL_CTL "$PLUGINSDIR\ready-close.bmp" $DL_IMG
  System::Call 'user32::SetWindowPos(p $DL_CTL, p 0, i 300, i 190, i 120, i 34, i 0x0014)'
  ${NSD_OnClick} $DL_CTL duolinkFinishClose

  nsDialogs::Show
FunctionEnd

Function duolinkFinishLeave
  Call duolinkShowButtons
FunctionEnd

Function duolinkOpenApp
  ${If} ${FileExists} "$INSTDIR\DuoLink.exe"
    Exec '"$INSTDIR\DuoLink.exe"'
  ${EndIf}
  SendMessage $HWNDPARENT ${WM_COMMAND} 2 0
FunctionEnd

Function duolinkFinishClose
  SendMessage $HWNDPARENT ${WM_COMMAND} 2 0
FunctionEnd

Function duolinkHideButtons
  GetDlgItem $0 $HWNDPARENT 1
  ShowWindow $0 ${SW_HIDE}
  GetDlgItem $0 $HWNDPARENT 2
  ShowWindow $0 ${SW_HIDE}
  GetDlgItem $0 $HWNDPARENT 3
  ShowWindow $0 ${SW_HIDE}
FunctionEnd

Function duolinkShowButtons
  GetDlgItem $0 $HWNDPARENT 1
  ShowWindow $0 ${SW_SHOW}
  GetDlgItem $0 $HWNDPARENT 2
  ShowWindow $0 ${SW_SHOW}
  GetDlgItem $0 $HWNDPARENT 3
  ShowWindow $0 ${SW_SHOW}
FunctionEnd

Function duolinkWelcomeCreate
  ; Unattended installs (/S) skip the branded page: it exists to be clicked,
  ; and nsDialogs would otherwise hold the silent install on a page nobody can
  ; see.
  ${If} ${Silent}
    Abort
  ${EndIf}
  Call duolinkHideButtons
  SetCtlColors $HWNDPARENT 0x161B1E 0xE2EEF3
  nsDialogs::Create 1018
  Pop $DL_PAGE
  ${If} $DL_PAGE == error
    Abort
  ${EndIf}
  SetCtlColors $DL_PAGE 0x161B1E 0xE2EEF3

  ${NSD_CreateBitmap} 0 0 0 0 ""
  Pop $DL_IMG
  ${NSD_SetImage} $DL_IMG "$PLUGINSDIR\setup-page.bmp" $DL_CTL
  System::Call 'user32::SetWindowPos(p $DL_IMG, p 0, i 0, i 0, i 490, i 238, i 0x0014)'
  ${NSD_OnClick} $DL_IMG duolinkPageClick

  ; Gold Install pill: a still image with SS_NOTIFY, so the button keeps the
  ; reference shape (a themed NSIS button cannot be rounded or recoloured).
  ${NSD_CreateBitmap} 0 0 0 0 ""
  Pop $DL_CTL
  ${NSD_SetImage} $DL_CTL "$PLUGINSDIR\setup-pill.bmp" $DL_IMG
  System::Call 'user32::SetWindowPos(p $DL_CTL, p 0, i 140, i 126, i 210, i 38, i 0x0014)'
  System::Call 'user32::SetWindowPos(p $DL_CTL, p 0, i 0, i 0, i 0, i 0, i 0x0003)'
  ${NSD_OnClick} $DL_CTL duolinkInstallClick


  nsDialogs::Show
FunctionEnd

Function duolinkWelcomeLeave
  Call duolinkShowButtons
FunctionEnd

Function duolinkInstallClick
  ; The same WM_COMMAND the wizard's own (hidden) Next button sends, so the
  ; options, directory and install pages still run normally.
  SendMessage $HWNDPARENT ${WM_COMMAND} 1 0
FunctionEnd

Function duolinkPageClick
  ; The pill advances; the underlined line under it explains what gets
  ; installed. Hit-testing the artwork is reliable where a transparent overlay
  ; was not, because the bitmap always receives the click.
  System::Alloc 8
  Pop $1
  System::Call 'user32::GetCursorPos(p r1)'
  System::Call 'user32::ScreenToClient(p $DL_PAGE, p r1)'
  System::Call '*$1(i .r2, i .r3)'
  System::Free $1
  ${If} $2 >= 140
  ${AndIf} $2 <= 350
  ${AndIf} $3 >= 126
  ${AndIf} $3 <= 164
    Call duolinkInstallClick
  ${EndIf}
FunctionEnd

!else

; ---- uninstaller pass ------------------------------------------------------

!define MUI_CUSTOMFUNCTION_UNGUIINIT un.duolinkUnGuiInit

Function un.duolinkUnGuiInit
  SetCtlColors $HWNDPARENT 0x161B1E 0xE2EEF3
FunctionEnd

!macro customUnInit
  InitPluginsDir
  File /oname=$PLUGINSDIR\uninstall-page.bmp "${BUILD_RESOURCES_DIR}\uninstall-page.bmp"
  File /oname=$PLUGINSDIR\uninstall-pill.bmp "${BUILD_RESOURCES_DIR}\uninstall-pill.bmp"
!macroend

!macro customUnWelcomePage
  UninstPage custom un.duolinkUnWelcomeCreate un.duolinkUnWelcomeLeave
!macroend

Function un.duolinkUnHideButtons
  GetDlgItem $0 $HWNDPARENT 1
  ShowWindow $0 ${SW_HIDE}
  GetDlgItem $0 $HWNDPARENT 2
  ShowWindow $0 ${SW_HIDE}
  GetDlgItem $0 $HWNDPARENT 3
  ShowWindow $0 ${SW_HIDE}
FunctionEnd

Function un.duolinkUnShowButtons
  GetDlgItem $0 $HWNDPARENT 1
  ShowWindow $0 ${SW_SHOW}
  GetDlgItem $0 $HWNDPARENT 2
  ShowWindow $0 ${SW_SHOW}
  GetDlgItem $0 $HWNDPARENT 3
  ShowWindow $0 ${SW_SHOW}
FunctionEnd

Function un.duolinkUnWelcomeCreate
  ${If} ${Silent}
    Abort
  ${EndIf}
  Call un.duolinkUnHideButtons
  SetCtlColors $HWNDPARENT 0x161B1E 0xE2EEF3
  nsDialogs::Create 1018
  Pop $DL_PAGE
  ${If} $DL_PAGE == error
    Abort
  ${EndIf}
  SetCtlColors $DL_PAGE 0x161B1E 0xE2EEF3

  ${NSD_CreateBitmap} 0 0 0 0 ""
  Pop $DL_IMG
  ${NSD_SetImage} $DL_IMG "$PLUGINSDIR\uninstall-page.bmp" $DL_CTL
  System::Call 'user32::SetWindowPos(p $DL_IMG, p 0, i 0, i 0, i 490, i 238, i 0x0014)'
  ${NSD_OnClick} $DL_IMG un.duolinkUnPageClick

  ${NSD_CreateBitmap} 0 0 0 0 ""
  Pop $DL_CTL
  ${NSD_SetImage} $DL_CTL "$PLUGINSDIR\uninstall-pill.bmp" $DL_IMG
  System::Call 'user32::SetWindowPos(p $DL_CTL, p 0, i 143, i 136, i 210, i 38, i 0x0014)'
  System::Call 'user32::SetWindowPos(p $DL_CTL, p 0, i 0, i 0, i 0, i 0, i 0x0003)'
  ${NSD_OnClick} $DL_CTL un.duolinkUnInstallClick


  nsDialogs::Show
FunctionEnd

Function un.duolinkUnWelcomeLeave
  Call un.duolinkUnShowButtons
FunctionEnd

Function un.duolinkUnPageClick
  System::Alloc 8
  Pop $1
  System::Call 'user32::GetCursorPos(p r1)'
  System::Call 'user32::ScreenToClient(p $DL_PAGE, p r1)'
  System::Call '*$1(i .r2, i .r3)'
  System::Free $1
  ${If} $2 >= 174
  ${AndIf} $2 <= 320
  ${AndIf} $3 >= 178
  ${AndIf} $3 <= 206
    Call un.duolinkKeepClick
  ${EndIf}
FunctionEnd

Function un.duolinkUnInstallClick
  SendMessage $HWNDPARENT ${WM_COMMAND} 1 0
FunctionEnd

Function un.duolinkKeepClick
  ; Same as Cancel -> closes the uninstaller without touching anything.
  SendMessage $HWNDPARENT ${WM_COMMAND} 2 0
FunctionEnd

!endif
