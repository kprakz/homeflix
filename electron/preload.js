const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('homeflix', {
  state: () => ipcRenderer.invoke('state'),
  addFolder: (type) => ipcRenderer.invoke('add-folder', type),
  setFolderType: (folder, type) => ipcRenderer.invoke('set-folder-type', folder, type),
  removeFolder: (folder) => ipcRenderer.invoke('remove-folder', folder),
  openFolder: (folder) => ipcRenderer.invoke('open-folder', folder),
  openBrowser: () => ipcRenderer.invoke('open-browser'),
  openWifiSettings: () => ipcRenderer.invoke('open-wifi-settings'),
  allowFirewall: () => ipcRenderer.invoke('allow-firewall'),
  copy: (text) => ipcRenderer.invoke('copy', text),
  setOpenAtLogin: (on) => ipcRenderer.invoke('set-open-at-login', on),
  clearCache: () => ipcRenderer.invoke('clear-cache'),
  setPin: (pin) => ipcRenderer.invoke('set-pin', pin),
});
