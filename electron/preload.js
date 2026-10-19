const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('homeflix', {
  state: () => ipcRenderer.invoke('state'),
  addFolder: () => ipcRenderer.invoke('add-folder'),
  removeFolder: (folder) => ipcRenderer.invoke('remove-folder', folder),
  openFolder: (folder) => ipcRenderer.invoke('open-folder', folder),
  openBrowser: () => ipcRenderer.invoke('open-browser'),
  openWifiSettings: () => ipcRenderer.invoke('open-wifi-settings'),
  allowFirewall: () => ipcRenderer.invoke('allow-firewall'),
  copy: (text) => ipcRenderer.invoke('copy', text),
  setOpenAtLogin: (on) => ipcRenderer.invoke('set-open-at-login', on),
  clearCache: () => ipcRenderer.invoke('clear-cache'),
});
