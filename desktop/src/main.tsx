import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';

// Expose Electron IPC type globally
declare global {
  interface Window {
    psphere: {
      selectFolder:  () => Promise<string | null>;
      openExternal:  (url: string) => Promise<void>;
      getAppVersion: () => Promise<string>;
      startSync: (opts: { folder: string; baseUrl: string; jwt: string; deviceToken: string }) => Promise<void>;
      onSyncProgress: (cb: (p: { stage: string; message: string; done?: number; total?: number }) => void) => () => void;
    };
  }
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
