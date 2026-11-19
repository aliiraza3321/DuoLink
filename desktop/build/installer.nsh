; ---------------------------------------------------------------------------
; DuoLink installer + uninstaller
;
; The reference design is a cream page with the DuoLink mark, a headline, a
; subtitle, a gold action pill and a panel of choices. MUI's stock welcome page
; cannot show that, so this include replaces it with an nsDialogs page: the
; paper and type come from build/make-installer-art.py, while every control a
; person can operate - Install, the desktop-shortcut checkbox, the install
; folder, "Change", "What this installs", Open and Close - is a real Windows
; control placed on top. Nothing clickable is painted into the bitmap: the old
; page drew its checkbox and links in the artwork, so they looked real and did
; nothing.
;
; The wizard's own Back/Next/Cancel row is hidden on these pages because the
; artwork carries the page's only actions.
;
; electron-builder compiles the script twice; functions a pass does not use
; trigger warning 6010 and warnings are errors here, so each pass defines only
; the pages it shows.
; ---------------------------------------------------------------------------

!include "nsDialogs.nsh"
!include "LogicLib.nsh"
!include "WinMessages.nsh"

; electron-builder always defines the MUI header strip for assisted installers.
; It repeated the product mark that every page already carries, and its grey-blue
; band cut the cream page in half, so the custom pages drop it. Guarded because
; !undef on an undefined name is a build error, and warnings are errors here.
!ifdef MUI_HEADERIMAGE
  !undef MUI_HEADERIMAGE
!endif
!ifdef MUI_HEADERIMAGE_RIGHT
  !undef MUI_HEADERIMAGE_RIGHT
!endif
!ifdef MUI_HEADERIMAGE_BITMAP
  !undef MUI_HEADERIMAGE_BITMAP
!endif

; MUI only colours its header strip from MUI_BGCOLOR; the page body and the
; footer come from the outer dialog's own colours, which is why the branded
; page used to sit inside a stock white window. MUI gives us a hook that runs
; right after it initialises that dialog.
!define MUI_CUSTOMFUNCTION_GUIINIT duolinkGuiInit

Function duolinkGuiInit
  ; Cream paper (#F3EEE2) with ink text (#1E1B16) - NSIS stores colours BGR.
  SetCtlColors $HWNDPARENT 0x161B1E 0xE2EEF3
  Call duolinkStyleHeader
FunctionEnd

; MUI's header strip defaults to a grey-blue band with the app icon on the
; right. Every page already carries the DuoLink mark, so the strip's copy of it
; is hidden and the band is painted the same cream as the pages: one logo, one
; continuous surface. The header controls do not exist yet when .onGUIInit
; runs, so this is called again from each page as it is created.
; 1034 header band, 1035/1036 the rules, 1037/1038 its title and subtitle,
; 1039 the icon, 1028 the footer line.
Function duolinkStyleHeader
  GetDlgItem $0 $HWNDPARENT 1039
  ${If} $0 != 0
    ShowWindow $0 ${SW_HIDE}
  ${EndIf}
  GetDlgItem $0 $HWNDPARENT 1034
  ${If} $0 != 0
    SetCtlColors $0 0x161B1E 0xE2EEF3
  ${EndIf}
  GetDlgItem $0 $HWNDPARENT 1037
  ${If} $0 != 0
    SetCtlColors $0 0x161B1E 0xE2EEF3
  ${EndIf}
  GetDlgItem $0 $HWNDPARENT 1038
  ${If} $0 != 0
    SetCtlColors $0 0x4E5A5F 0xE2EEF3
  ${EndIf}
  GetDlgItem $0 $HWNDPARENT 1035
  ${If} $0 != 0
    SetCtlColors $0 0x4E5A5F 0xE2EEF3
  ${EndIf}
  GetDlgItem $0 $HWNDPARENT 1036
  ${If} $0 != 0
    SetCtlColors $0 0x4E5A5F 0xE2EEF3
  ${EndIf}
  GetDlgItem $0 $HWNDPARENT 1028
  ${If} $0 != 0
    SetCtlColors $0 0x4E5A5F 0xE2EEF3
  ${EndIf}
FunctionEnd

; Palette used by the remaining MUI pages (install mode, directory, installing,
; finish). NSIS colours are BGR: 0xE2EEF3 = #F3EEE2 cream, 0x161B1E = #1E1B16.
!define MUI_BGCOLOR 0xE2EEF3
!define MUI_TEXTCOLOR 0x161B1E
!define MUI_HEADER_TRANSPARENT_TEXT
!define MUI_INSTFILESPAGE_COLORS "0x161B1E 0xE2EEF3"
!define MUI_INSTFILESPAGE_PROGRESSBAR colored

Var DL_PAGE        ; nsDialogs page handle
Var DL_PAGEBMP     ; full-page bitmap (draws the artwork, receives clicks)
Var DL_MASK        ; SetImage mask output - never the page handle
Var DL_CTL         ; the pill / button bitmap currently being built

!ifndef BUILD_UNINSTALLER

; ---- installer pass --------------------------------------------------------

; Installer-only: the uninstaller pass never touches these, and an unused
; variable is a build error (warnings are errors).
Var DL_CHECK       ; "Create a desktop shortcut" checkbox
Var DL_PATHLBL     ; the concrete install folder, shown as text
Var DL_DESKTOP     ; "1" when the desktop shortcut should be created

; The artwork is a full-page STATIC. Siblings created after it can end up
; underneath it, which is exactly what made the Install pill and the option
; panel look empty, so the picture is pushed to the bottom of the z-order once
; the page's real controls exist.
Function duolinkSendArtworkBack
  System::Call 'user32::SetWindowPos(p $DL_PAGEBMP, i 1, i 0, i 0, i 0, i 0, i 0x0013)'
FunctionEnd

!macro customInit
  InitPluginsDir
  ; Silent installs (/S) never see the page, so the default has to exist here.
  StrCpy $DL_DESKTOP "1"
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

; Delete the shortcut electron-builder just created when the checkbox on the
; welcome page was cleared. customInstall runs after addDesktopLink in
; installSection.nsh, so the file exists by now.
!macro customInstall
  ${If} $DL_DESKTOP != "1"
    ${If} ${FileExists} "$newDesktopLink"
      Delete "$newDesktopLink"
    ${EndIf}
    System::Call 'Shell32::SHChangeNotify(i 0x8000000, i 0, i 0, i 0)'
  ${EndIf}
!macroend

; ---- shared helpers --------------------------------------------------------

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

; Cursor position inside the page, in the same pixels the artwork is laid out
; in. Bitmap controls do not reliably report clicks (STATIC controls without
; SS_NOTIFY swallow them), so every pill is hit-tested here - that is why the
; Install button worked on the old page while Open and Close did not.
Function duolinkCursorInPage
  System::Alloc 8
  Pop $1
  System::Call 'user32::GetCursorPos(p r1)'
  System::Call 'user32::ScreenToClient(p $DL_PAGE, p r1)'
  System::Call '*$1(i .r2, i .r3)'
  System::Free $1
FunctionEnd

; ---- welcome page ----------------------------------------------------------

Function duolinkWelcomeCreate
  ; Unattended installs (/S) skip the branded page: it exists to be clicked,
  ; and nsDialogs would otherwise hold the silent install on a page nobody can
  ; see.
  ${If} ${Silent}
    Abort
  ${EndIf}
  Call duolinkHideButtons
  Call duolinkStyleHeader
  SetCtlColors $HWNDPARENT 0x161B1E 0xE2EEF3
  nsDialogs::Create 1018
  Pop $DL_PAGE
  ${If} $DL_PAGE == error
    Abort
  ${EndIf}
  SetCtlColors $DL_PAGE 0x161B1E 0xE2EEF3

  ; Paper, mark, headline and the option panel - one bitmap.
  ${NSD_CreateBitmap} 0 0 0 0 ""
  Pop $DL_PAGEBMP
  ${NSD_SetImage} $DL_PAGEBMP "$PLUGINSDIR\setup-page.bmp" $DL_MASK
  System::Call 'user32::SetWindowPos(p $DL_PAGEBMP, p 0, i 0, i 0, i 450, i 228, i 0x0014)'
  ${NSD_OnClick} $DL_PAGEBMP duolinkPageClick

  ; Gold Install pill: a still image, so it keeps the reference shape (a themed
  ; NSIS button cannot be rounded or recoloured).
  ${NSD_CreateBitmap} 0 0 0 0 ""
  Pop $DL_CTL
  ${NSD_SetImage} $DL_CTL "$PLUGINSDIR\setup-pill.bmp" $DL_MASK
  System::Call 'user32::SetWindowPos(p $DL_CTL, p 0, i 125, i 118, i 200, i 36, i 0x0014)'
  ${NSD_OnClick} $DL_CTL duolinkInstallClick

  ; ---- real controls, on the panel the artwork draws ----
  ; `transparent` keeps the artwork's panel visible behind each control - a
  ; coloured background left white boxes on the cream card.
  ${NSD_CreateCheckbox} 40 166 240 12 "Create a desktop shortcut"
  Pop $DL_CHECK
  ${NSD_Check} $DL_CHECK
  SetCtlColors $DL_CHECK 0x161B1E transparent

  ${NSD_CreateLabel} 40 183 296 11 ""
  Pop $DL_PATHLBL
  SetCtlColors $DL_PATHLBL 0x8C8472 transparent
  Call duolinkShowInstallPath

  ${NSD_CreateLink} 352 183 60 11 "Change"
  Pop $DL_CTL
  ${NSD_OnClick} $DL_CTL duolinkPickFolder
  SetCtlColors $DL_CTL 0x20668C transparent

  ${NSD_CreateLink} 165 199 120 11 "What this installs"
  Pop $DL_CTL
  ${NSD_OnClick} $DL_CTL duolinkWhatInstalls
  SetCtlColors $DL_CTL 0x20668C transparent

  Call duolinkSendArtworkBack
  nsDialogs::Show
FunctionEnd

Function duolinkWelcomeLeave
  ${NSD_GetState} $DL_CHECK $DL_DESKTOP
  Call duolinkShowButtons
FunctionEnd

Function duolinkInstallClick
  ; The same WM_COMMAND the wizard's own (hidden) Next button sends, so the
  ; install-mode, directory and install pages still run normally.
  SendMessage $HWNDPARENT ${WM_COMMAND} 1 0
FunctionEnd

Function duolinkPageClick
  ; Hit-test the pill: the artwork always receives the click, so this is more
  ; reliable than relying on a STATIC control to report one.
  Call duolinkCursorInPage
  ${If} $2 >= 122
  ${AndIf} $2 <= 328
  ${AndIf} $3 >= 115
  ${AndIf} $3 <= 157
    Call duolinkInstallClick
  ${EndIf}
FunctionEnd

Function duolinkPickFolder
  nsDialogs::SelectFolderDialog "Choose where DuoLink should be installed" "$INSTDIR"
  Pop $0
  ${If} $0 == error
    Return
  ${EndIf}
  ; Keep the app in its own folder, the way $INSTDIR is shaped everywhere else.
  StrCpy $1 $0 7 -7
  ${If} $1 == "DuoLink"
    StrCpy $INSTDIR $0
  ${Else}
    StrCpy $INSTDIR "$0\DuoLink"
  ${EndIf}
  Call duolinkShowInstallPath
FunctionEnd

; The real path is long enough to run into the Change link, so a path under the
; user profile is shown with its %LOCALAPPDATA% shorthand.
Function duolinkShowInstallPath
  StrCpy $1 ""
  ${If} $DL_PATHLBL == 0
    Return
  ${EndIf}
  StrCpy $2 $INSTDIR 13
  ${If} $2 == "$LOCALAPPDATA"
    StrCpy $2 $INSTDIR "" 13
    StrCpy $1 "%LOCALAPPDATA%$2"
  ${Else}
    StrCpy $1 $INSTDIR
  ${EndIf}
  SendMessage $DL_PATHLBL ${WM_SETTEXT} 0 "STR:$1"
FunctionEnd

Function duolinkWhatInstalls
  ; NSIS strings are single-line, so the copy is assembled instead of wrapped.
  StrCpy $0 "DuoLink installs for the current user:$\r$\n$\r$\n"
  StrCpy $0 "$0- DuoLink.exe and its runtime$\r$\n"
  StrCpy $0 "$0- A Start Menu shortcut$\r$\n"
  StrCpy $0 "$0- A desktop shortcut (untick the box to skip it)$\r$\n"
  StrCpy $0 "$0- An uninstaller in Apps & features$\r$\n"
  StrCpy $0 "$0- A Windows Firewall rule for private networks only$\r$\n$\r$\n"
  StrCpy $0 "$0Pairings, settings and received files live outside that folder and go only when you uninstall."
  MessageBox MB_ICONINFORMATION|MB_OK "$0"
FunctionEnd

; ---- finish page -----------------------------------------------------------

Function duolinkFinishCreate
  ${If} ${Silent}
    Abort
  ${EndIf}
  Call duolinkHideButtons
  Call duolinkStyleHeader
  SetCtlColors $HWNDPARENT 0x161B1E 0xE2EEF3
  nsDialogs::Create 1018
  Pop $DL_PAGE
  ${If} $DL_PAGE == error
    Abort
  ${EndIf}
  SetCtlColors $DL_PAGE 0x161B1E 0xE2EEF3

  ${NSD_CreateBitmap} 0 0 0 0 ""
  Pop $DL_PAGEBMP
  ${NSD_SetImage} $DL_PAGEBMP "$PLUGINSDIR\ready-page.bmp" $DL_MASK
  System::Call 'user32::SetWindowPos(p $DL_PAGEBMP, p 0, i 0, i 0, i 450, i 228, i 0x0014)'
  ${NSD_OnClick} $DL_PAGEBMP duolinkFinishPageClick

  ; Open DuoLink (filled pill) and Close (outlined pill).
  ${NSD_CreateBitmap} 0 0 0 0 ""
  Pop $DL_CTL
  ${NSD_SetImage} $DL_CTL "$PLUGINSDIR\ready-open.bmp" $DL_MASK
  System::Call 'user32::SetWindowPos(p $DL_CTL, p 0, i 74, i 186, i 170, i 34, i 0x0014)'
  ${NSD_OnClick} $DL_CTL duolinkOpenApp

  ${NSD_CreateBitmap} 0 0 0 0 ""
  Pop $DL_CTL
  ${NSD_SetImage} $DL_CTL "$PLUGINSDIR\ready-close.bmp" $DL_MASK
  System::Call 'user32::SetWindowPos(p $DL_CTL, p 0, i 256, i 186, i 120, i 34, i 0x0014)'
  ${NSD_OnClick} $DL_CTL duolinkFinishClose

  Call duolinkSendArtworkBack
  nsDialogs::Show
FunctionEnd

Function duolinkFinishLeave
  Call duolinkShowButtons
FunctionEnd

; Both pills are hit-tested on the page bitmap, so Close works even when the
; STATIC control itself never reports the click - the last page used to have no
; working exit at all.
Function duolinkFinishPageClick
  Call duolinkCursorInPage
  ${If} $3 >= 184
  ${AndIf} $3 <= 222
    ${If} $2 >= 72
    ${AndIf} $2 <= 246
      Call duolinkOpenApp
      Return
    ${EndIf}
    ${If} $2 >= 254
    ${AndIf} $2 <= 378
      Call duolinkFinishClose
    ${EndIf}
  ${EndIf}
FunctionEnd

Function duolinkOpenApp
  ${If} ${FileExists} "$INSTDIR\DuoLink.exe"
    Exec '"$INSTDIR\DuoLink.exe"'
  ${EndIf}
  Call duolinkFinishClose
FunctionEnd

Function duolinkFinishClose
  ; The finish page only accepts its Finish/Close button (ID 1). Sending Cancel
  ; (ID 2) - what this used to do - is silently ignored on a finish page, so the
  ; last page with the QR code could not be closed at all.
  SendMessage $HWNDPARENT ${WM_COMMAND} 1 0
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

; Uninstaller twin of duolinkStyleHeader: an uninstaller page cannot call an
; installer function, so the header is dressed here too. Same controls, same
; cream band, no second logo.
Function un.duolinkStyleHeader
  GetDlgItem $0 $HWNDPARENT 1039
  ${If} $0 != 0
    ShowWindow $0 ${SW_HIDE}
  ${EndIf}
  GetDlgItem $0 $HWNDPARENT 1034
  ${If} $0 != 0
    SetCtlColors $0 0x161B1E 0xE2EEF3
  ${EndIf}
  GetDlgItem $0 $HWNDPARENT 1037
  ${If} $0 != 0
    SetCtlColors $0 0x161B1E 0xE2EEF3
  ${EndIf}
  GetDlgItem $0 $HWNDPARENT 1038
  ${If} $0 != 0
    SetCtlColors $0 0x4E5A5F 0xE2EEF3
  ${EndIf}
  GetDlgItem $0 $HWNDPARENT 1028
  ${If} $0 != 0
    SetCtlColors $0 0x4E5A5F 0xE2EEF3
  ${EndIf}
FunctionEnd

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
  Call un.duolinkStyleHeader
  SetCtlColors $HWNDPARENT 0x161B1E 0xE2EEF3
  nsDialogs::Create 1018
  Pop $DL_PAGE
  ${If} $DL_PAGE == error
    Abort
  ${EndIf}
  SetCtlColors $DL_PAGE 0x161B1E 0xE2EEF3

  ${NSD_CreateBitmap} 0 0 0 0 ""
  Pop $DL_PAGEBMP
  ${NSD_SetImage} $DL_PAGEBMP "$PLUGINSDIR\uninstall-page.bmp" $DL_MASK
  System::Call 'user32::SetWindowPos(p $DL_PAGEBMP, p 0, i 0, i 0, i 450, i 228, i 0x0014)'
  ${NSD_OnClick} $DL_PAGEBMP un.duolinkUnPageClick

  ${NSD_CreateBitmap} 0 0 0 0 ""
  Pop $DL_CTL
  ${NSD_SetImage} $DL_CTL "$PLUGINSDIR\uninstall-pill.bmp" $DL_MASK
  System::Call 'user32::SetWindowPos(p $DL_CTL, p 0, i 120, i 136, i 210, i 38, i 0x0014)'
  ${NSD_OnClick} $DL_CTL un.duolinkUnInstallClick

  System::Call 'user32::SetWindowPos(p $DL_PAGEBMP, i 1, i 0, i 0, i 0, i 0, i 0x0013)'
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
  ${If} $2 >= 120
  ${AndIf} $2 <= 330
  ${AndIf} $3 >= 136
  ${AndIf} $3 <= 174
    Call un.duolinkUnInstallClick
  ${EndIf}
FunctionEnd

Function un.duolinkUnInstallClick
  SendMessage $HWNDPARENT ${WM_COMMAND} 1 0
FunctionEnd

; Cancelling the uninstall page is Esc (or the title-bar X): the page's only
; button is Uninstall, so no handler is needed for "keep it".

!endif
