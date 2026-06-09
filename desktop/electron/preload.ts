import { contextBridge, ipcRenderer } from 'electron';

contextBridge.exposeInMainWorld('psphere', {
  selectFolder:   (): Promise<string | null> =>
    ipcRenderer.invoke('select-folder'),

  openExternal:   (url: string): Promise<void> =>
    ipcRenderer.invoke('open-external', url),

  getAppVersion:  (): Promise<string> =>
    ipcRenderer.invoke('get-app-version'),

  startSync: (opts: {
    folder: string; baseUrl: string; jwt: string; deviceToken: string;
  }): Promise<void> =>
    ipcRenderer.invoke('sync-folder-start', opts),

  onSyncProgress: (
    cb: (progress: { stage: string; message: string; done?: number; total?: number }) => void
  ) => {
    ipcRenderer.on('sync-progress', (_event, data) => cb(data));
    // Return cleanup function
    return () => ipcRenderer.removeAllListeners('sync-progress');
  },
});
