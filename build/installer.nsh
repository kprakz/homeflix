; Remove HOMEFLIX's converted movie copies and thumbnails on uninstall (they can be several GB).
; The user's own movie folders are never touched.
!macro customUnInstall
  RMDir /r "$LOCALAPPDATA\HOMEFLIX\cache"
  RMDir "$LOCALAPPDATA\HOMEFLIX"
!macroend
